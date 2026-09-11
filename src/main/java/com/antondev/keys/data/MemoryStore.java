package com.antondev.keys.data;

import com.antondev.keys.model.KeyTier;
import java.util.*;

/**
 * Memory-authoritative gameplay state. Normal access remains serialized so Bukkit's main thread never
 * races the database worker; save handoff uses immutable, versioned deltas only.
 */
public final class MemoryStore {
    public static final long HARD_LIMIT = 1_000_000_000L;
    public static final int MAX_CONSUME_REPLAY_RECORDS = 4096;
    public static final long CONSUME_REPLAY_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final int MAX_CONSUME_REPLAY_IN_MEMORY = MAX_CONSUME_REPLAY_RECORDS + 1;

    public record Account(UUID player, String name, long basic, long rare, long epic, long legendary) {
        public long amount(KeyTier tier) {
            return switch (tier) {
                case BASIC -> basic;
                case RARE -> rare;
                case EPIC -> epic;
                case LEGENDARY -> legendary;
            };
        }
    }

    /** Exact result from one authoritative balance mutation. */
    public record BalanceMutation(long previous, long delta, long current) {}

    /** Durable idempotency record for the public/API consume boundary. */
    public record ConsumeReplay(String transactionId, UUID player, KeyTier tier, long amount, long createdAtEpochMillis) {
        public ConsumeReplay {
            if (transactionId == null || transactionId.isBlank() || transactionId.length() > 128) {
                throw new IllegalArgumentException("transactionId must contain 1-128 characters");
            }
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(tier, "tier");
            if (amount <= 0 || amount > HARD_LIMIT) {
                throw new IllegalArgumentException("amount must be between 1 and " + HARD_LIMIT);
            }
            if (createdAtEpochMillis < 0) throw new IllegalArgumentException("createdAtEpochMillis must be non-negative");
        }
    }

    public record ConsumeMutation(boolean duplicate, boolean success, long balance) {}

    public record ProvenanceMetrics(
            double seconds,
            long totalPositions,
            Map<UUID, Integer> perWorld,
            long marks,
            long unmarks,
            long moves,
            int largestBatch) {
        public double marksPerSecond() { return rate(marks); }
        public double unmarksPerSecond() { return rate(unmarks); }
        public double movesPerSecond() { return rate(moves); }
        private double rate(long value) { return seconds <= 0 ? 0.0 : value / seconds; }
    }

    public record Position(UUID world, long packed) {
        public static Position of(UUID world, int x, int y, int z) {
            if (x < -33_554_432 || x > 33_554_431 || z < -33_554_432 || z > 33_554_431 || y < -2048 || y > 2047) {
                throw new IllegalArgumentException("Block coordinate exceeds Minecraft's packed position range");
            }
            return new Position(world, ((long) x & 0x3ffffff) << 38 | ((long) z & 0x3ffffff) << 12 | (y & 0xfff));
        }
    }

    public record Versioned<T>(long version, T value) {}

    public record Snapshot(
            Map<UUID, Versioned<Account>> accounts,
            Map<Position, Versioned<Boolean>> blocks,
            Map<String, Versioned<ConsumeReplay>> consumes) {
        public Snapshot {
            accounts = Map.copyOf(accounts);
            blocks = Map.copyOf(blocks);
            consumes = Map.copyOf(consumes);
        }
        public Snapshot(Map<UUID, Versioned<Account>> accounts, Map<Position, Versioned<Boolean>> blocks) {
            this(accounts, blocks, Map.of());
        }
        public boolean empty() { return accounts.isEmpty() && blocks.isEmpty() && consumes.isEmpty(); }
        public int size() { return accounts.size() + blocks.size() + consumes.size(); }
    }

    private final Map<UUID, Account> accounts = new HashMap<>();
    private final Map<String, UUID> playersByName = new HashMap<>();
    private final Map<UUID, Set<Long>> artificial = new HashMap<>();
    private final LinkedHashMap<String, ConsumeReplay> consumeReplays = new LinkedHashMap<>();
    private final Map<UUID, Versioned<Account>> dirtyAccounts = new HashMap<>();
    private final Map<Position, Versioned<Boolean>> dirtyBlocks = new HashMap<>();
    private final LinkedHashMap<String, Versioned<ConsumeReplay>> dirtyConsumeReplays = new LinkedHashMap<>();
    private final long metricsStartNanos = System.nanoTime();
    private List<String> cachedNames = List.of();
    private boolean namesDirty = true;
    private long version;
    private long blockCount;
    private long provenanceMarks;
    private long provenanceUnmarks;
    private long provenanceMoves;
    private int largestProvenanceBatch;

    public synchronized void loadAccount(Account account) {
        Objects.requireNonNull(account, "account");
        for (KeyTier tier : KeyTier.values()) validate(account.amount(tier));
        Account previous = accounts.put(account.player(), account);
        updateNameIndex(previous, account);
    }

    public synchronized void loadBlock(Position position) {
        Objects.requireNonNull(position, "position");
        if (artificial.computeIfAbsent(position.world(), ignored -> new HashSet<>()).add(position.packed())) blockCount++;
    }

    public synchronized void loadConsumeReplay(ConsumeReplay replay) {
        Objects.requireNonNull(replay, "replay");
        ConsumeReplay previous = consumeReplays.putIfAbsent(replay.transactionId(), replay);
        if (previous != null && !sameConsume(previous, replay.player(), replay.tier(), replay.amount())) {
            throw new IllegalArgumentException("Conflicting persisted transactionId: " + replay.transactionId());
        }
        pruneConsumeReplayCache(System.currentTimeMillis());
    }

    public synchronized Account account(UUID player) {
        return current(player);
    }

    public synchronized void remember(UUID player, String name) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(name, "name");
        Account old = current(player);
        if (!Objects.equals(old.name(), name)) {
            put(new Account(player, name, old.basic(), old.rare(), old.epic(), old.legendary()));
        }
    }

    public synchronized Optional<UUID> findPlayer(String input) {
        Objects.requireNonNull(input, "input");
        try {
            return Optional.of(UUID.fromString(input));
        } catch (IllegalArgumentException ignored) {
            return Optional.ofNullable(playersByName.get(normalize(input)));
        }
    }

    public synchronized List<String> names() {
        if (namesDirty) {
            cachedNames = accounts.values().stream()
                    .map(Account::name)
                    .filter(name -> !name.isEmpty())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            namesDirty = false;
        }
        return cachedNames;
    }

    public synchronized long balance(UUID player, KeyTier tier) {
        return current(player).amount(Objects.requireNonNull(tier, "tier"));
    }

    public synchronized Map<KeyTier, Long> balances(UUID player) {
        Account account = current(player);
        EnumMap<KeyTier, Long> result = new EnumMap<>(KeyTier.class);
        result.put(KeyTier.BASIC, account.basic());
        result.put(KeyTier.RARE, account.rare());
        result.put(KeyTier.EPIC, account.epic());
        result.put(KeyTier.LEGENDARY, account.legendary());
        return Map.copyOf(result);
    }

    public synchronized long credit(UUID player, KeyTier tier, long requested, long cap) {
        return credit(player, null, tier, requested, cap).delta();
    }

    /**
     * Credit and optional player-name refresh in one authoritative account replacement. This avoids a
     * remember()+credit() pair producing two dirty versions for one reward.
     */
    public synchronized BalanceMutation credit(UUID player, String name, KeyTier tier, long requested, long cap) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(tier, "tier");
        validate(requested);
        validate(cap);
        Account old = current(player);
        long previous = old.amount(tier);
        long added = Math.min(requested, Math.max(0L, cap - previous));
        String nextName = name == null ? old.name() : Objects.requireNonNull(name, "name");
        if (added == 0 && Objects.equals(old.name(), nextName)) return new BalanceMutation(previous, 0, previous);
        long current = previous + added;
        put(with(old, nextName, tier, current));
        return new BalanceMutation(previous, added, current);
    }

    public synchronized void set(UUID player, KeyTier tier, long amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(tier, "tier");
        validate(amount);
        Account old = current(player);
        if (old.amount(tier) == amount) return;
        put(with(old, old.name(), tier, amount));
    }

    public synchronized BalanceMutation take(UUID player, KeyTier tier, long requested) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(tier, "tier");
        validate(requested);
        Account old = current(player);
        long previous = old.amount(tier);
        long removed = Math.min(previous, requested);
        if (removed == 0) return new BalanceMutation(previous, 0, previous);
        long current = previous - removed;
        put(with(old, old.name(), tier, current));
        return new BalanceMutation(previous, -removed, current);
    }

    /** Validate all requested tiers and replace the account exactly once. */
    public synchronized boolean debit(UUID player, Map<KeyTier, Long> amounts) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(amounts, "amounts");
        Account old = current(player);
        long basic = old.basic(), rare = old.rare(), epic = old.epic(), legendary = old.legendary();
        for (var entry : amounts.entrySet()) {
            KeyTier tier = Objects.requireNonNull(entry.getKey(), "tier");
            Long boxed = Objects.requireNonNull(entry.getValue(), "amount");
            long requested = boxed;
            validate(requested);
            if (old.amount(tier) < requested) return false;
            switch (tier) {
                case BASIC -> basic -= requested;
                case RARE -> rare -= requested;
                case EPIC -> epic -= requested;
                case LEGENDARY -> legendary -= requested;
            }
        }
        if (basic == old.basic() && rare == old.rare() && epic == old.epic() && legendary == old.legendary()) return true;
        put(new Account(player, old.name(), basic, rare, epic, legendary));
        return true;
    }

    /**
     * Atomically reserve a consume transaction in memory together with its balance debit. The account and
     * replay record share one version so the bounded persistence snapshot can commit them in one SQLite transaction.
     */
    public synchronized ConsumeMutation consume(UUID player, KeyTier tier, long amount, String transactionId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(tier, "tier");
        validate(amount);
        if (amount == 0) throw new IllegalArgumentException("amount must be positive");
        if (transactionId == null || transactionId.isBlank() || transactionId.length() > 128) {
            throw new IllegalArgumentException("transactionId must contain 1-128 characters");
        }

        ConsumeReplay previous = consumeReplays.get(transactionId);
        if (previous != null) {
            if (!sameConsume(previous, player, tier, amount)) {
                throw new IllegalArgumentException("transactionId was already used for a different consume request");
            }
            return new ConsumeMutation(true, false, current(player).amount(tier));
        }
        if (consumeReplays.size() >= MAX_CONSUME_REPLAY_IN_MEMORY) {
            throw new IllegalStateException("consume replay guard is full; persist pending transactions before accepting another id");
        }

        Account old = current(player);
        boolean success = old.amount(tier) >= amount;
        long nextVersion = ++version;
        long currentBalance = old.amount(tier);
        if (success) {
            currentBalance -= amount;
            Account next = with(old, old.name(), tier, currentBalance);
            Account replaced = accounts.put(player, next);
            updateNameIndex(replaced, next);
            dirtyAccounts.put(player, new Versioned<>(nextVersion, next));
        }

        ConsumeReplay replay = new ConsumeReplay(transactionId, player, tier, amount, System.currentTimeMillis());
        consumeReplays.put(transactionId, replay);
        dirtyConsumeReplays.put(transactionId, new Versioned<>(nextVersion, replay));
        return new ConsumeMutation(false, success, currentBalance);
    }

    public synchronized boolean consumeReplayDirty(String transactionId) {
        return dirtyConsumeReplays.containsKey(transactionId);
    }

    public synchronized Optional<ConsumeReplay> consumeReplay(String transactionId) {
        return Optional.ofNullable(consumeReplays.get(transactionId));
    }

    /** Restore a multi-tier claim debit with one account replacement and one dirty version. */
    public synchronized void restore(UUID player, Map<KeyTier, Long> amounts) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(amounts, "amounts");
        Account old = current(player);
        long basic = old.basic(), rare = old.rare(), epic = old.epic(), legendary = old.legendary();
        for (var entry : amounts.entrySet()) {
            KeyTier tier = Objects.requireNonNull(entry.getKey(), "tier");
            Long boxed = Objects.requireNonNull(entry.getValue(), "amount");
            long value = boxed;
            validate(value);
            switch (tier) {
                case BASIC -> basic = checkedAdd(basic, value);
                case RARE -> rare = checkedAdd(rare, value);
                case EPIC -> epic = checkedAdd(epic, value);
                case LEGENDARY -> legendary = checkedAdd(legendary, value);
            }
        }
        if (basic == old.basic() && rare == old.rare() && epic == old.epic() && legendary == old.legendary()) return;
        put(new Account(player, old.name(), basic, rare, epic, legendary));
    }

    private Account current(UUID player) {
        Objects.requireNonNull(player, "player");
        Account account = accounts.get(player);
        return account != null ? account : new Account(player, "", 0, 0, 0, 0);
    }

    private static Account with(Account account, String name, KeyTier tier, long amount) {
        return new Account(account.player(), name,
                tier == KeyTier.BASIC ? amount : account.basic(),
                tier == KeyTier.RARE ? amount : account.rare(),
                tier == KeyTier.EPIC ? amount : account.epic(),
                tier == KeyTier.LEGENDARY ? amount : account.legendary());
    }

    private void put(Account account) {
        Account previous = accounts.put(account.player(), account);
        updateNameIndex(previous, account);
        dirtyAccounts.put(account.player(), new Versioned<>(++version, account));
    }

    private void updateNameIndex(Account previous, Account current) {
        if (previous != null && !previous.name().isEmpty() && !Objects.equals(previous.name(), current.name())) {
            playersByName.remove(normalize(previous.name()), previous.player());
            namesDirty = true;
        }
        if (!current.name().isEmpty()) {
            String normalized = normalize(current.name());
            UUID existing = playersByName.put(normalized, current.player());
            if (!Objects.equals(existing, current.player()) || previous == null || !Objects.equals(previous.name(), current.name())) {
                namesDirty = true;
            }
        }
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static long checkedAdd(long current, long amount) {
        if (amount > HARD_LIMIT - current) throw new IllegalArgumentException("Restored amount exceeds " + HARD_LIMIT);
        return current + amount;
    }

    private static void validate(long amount) {
        if (amount < 0 || amount > HARD_LIMIT) throw new IllegalArgumentException("Amount must be between 0 and " + HARD_LIMIT);
    }

    private static boolean sameConsume(ConsumeReplay replay, UUID player, KeyTier tier, long amount) {
        return replay.player().equals(player) && replay.tier() == tier && replay.amount() == amount;
    }

    private void pruneConsumeReplayCache(long now) {
        long cutoff = Math.max(0L, now - CONSUME_REPLAY_RETENTION_MILLIS);
        Iterator<Map.Entry<String, ConsumeReplay>> iterator = consumeReplays.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ConsumeReplay> entry = iterator.next();
            if (dirtyConsumeReplays.containsKey(entry.getKey())) continue;
            if (entry.getValue().createdAtEpochMillis() < cutoff || consumeReplays.size() > MAX_CONSUME_REPLAY_RECORDS) {
                iterator.remove();
            }
        }
    }

    public synchronized boolean artificial(Position position) {
        Objects.requireNonNull(position, "position");
        Set<Long> set = artificial.get(position.world());
        return set != null && set.contains(position.packed());
    }

    public synchronized void mark(Position position) {
        if (markInternal(Objects.requireNonNull(position, "position"))) largestProvenanceBatch = Math.max(largestProvenanceBatch, 1);
    }

    public synchronized int markAll(Collection<Position> positions) {
        Objects.requireNonNull(positions, "positions");
        int changed = 0;
        for (Position position : positions) if (markInternal(Objects.requireNonNull(position, "position"))) changed++;
        if (changed > 0) largestProvenanceBatch = Math.max(largestProvenanceBatch, positions.size());
        return changed;
    }

    public synchronized boolean unmark(Position position) {
        boolean changed = unmarkInternal(Objects.requireNonNull(position, "position"));
        if (changed) largestProvenanceBatch = Math.max(largestProvenanceBatch, 1);
        return changed;
    }

    public synchronized int unmarkAll(Collection<Position> positions) {
        Objects.requireNonNull(positions, "positions");
        int changed = 0;
        for (Position position : positions) if (unmarkInternal(Objects.requireNonNull(position, "position"))) changed++;
        if (changed > 0) largestProvenanceBatch = Math.max(largestProvenanceBatch, positions.size());
        return changed;
    }

    private boolean markInternal(Position position) {
        if (!artificial.computeIfAbsent(position.world(), ignored -> new HashSet<>()).add(position.packed())) return false;
        blockCount++;
        provenanceMarks++;
        dirtyBlocks.put(position, new Versioned<>(++version, true));
        return true;
    }

    private boolean unmarkInternal(Position position) {
        Set<Long> set = artificial.get(position.world());
        if (set == null || !set.remove(position.packed())) return false;
        if (set.isEmpty()) artificial.remove(position.world());
        blockCount--;
        provenanceUnmarks++;
        dirtyBlocks.put(position, new Versioned<>(++version, false));
        return true;
    }

    /** Snapshot all source provenance before clearing so adjacent piston moves cannot overwrite each other. */
    public synchronized void move(Map<Position, Position> moves) {
        Objects.requireNonNull(moves, "moves");
        if (moves.isEmpty()) return;
        ArrayList<Position> artificialTargets = new ArrayList<>();
        for (var entry : moves.entrySet()) {
            Position source = Objects.requireNonNull(entry.getKey(), "source");
            Position target = Objects.requireNonNull(entry.getValue(), "target");
            Set<Long> sourceSet = artificial.get(source.world());
            if (sourceSet != null && sourceSet.contains(source.packed())) artificialTargets.add(target);
        }
        for (Position source : moves.keySet()) unmarkInternal(source);
        for (Position target : moves.values()) unmarkInternal(target);
        for (Position target : artificialTargets) markInternal(target);
        provenanceMoves += artificialTargets.size();
        largestProvenanceBatch = Math.max(largestProvenanceBatch, moves.size());
    }

    public synchronized Snapshot snapshot() {
        return snapshot(Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    /**
     * Copy at most {@code maximumRecords} dirty rows. Consume replay entries are selected first and paired
     * with their current dirty account row so a successful debit and its idempotency record enter the same
     * SQLite transaction. Other rows remain bounded by the requested revision boundary.
     */
    public synchronized Snapshot snapshot(int maximumRecords, long upToVersion) {
        if (maximumRecords < 1) throw new IllegalArgumentException("maximumRecords must be positive");
        LinkedHashMap<UUID, Versioned<Account>> accountCopy = new LinkedHashMap<>();
        LinkedHashMap<Position, Versioned<Boolean>> blockCopy = new LinkedHashMap<>();
        LinkedHashMap<String, Versioned<ConsumeReplay>> consumeCopy = new LinkedHashMap<>();
        int remaining = maximumRecords;

        for (var entry : dirtyConsumeReplays.entrySet()) {
            if (entry.getValue().version() > upToVersion) continue;
            Versioned<Account> account = dirtyAccounts.get(entry.getValue().value().player());
            int required = 1 + (account != null && !accountCopy.containsKey(entry.getValue().value().player()) ? 1 : 0);
            if (remaining < required) break;
            if (account != null) {
                accountCopy.put(entry.getValue().value().player(), account);
                remaining--;
            }
            consumeCopy.put(entry.getKey(), entry.getValue());
            remaining--;
        }

        for (var entry : dirtyAccounts.entrySet()) {
            if (remaining == 0) break;
            if (accountCopy.containsKey(entry.getKey())) continue;
            if (entry.getValue().version() <= upToVersion) {
                accountCopy.put(entry.getKey(), entry.getValue());
                remaining--;
            }
        }
        if (remaining > 0) {
            for (var entry : dirtyBlocks.entrySet()) {
                if (remaining == 0) break;
                if (entry.getValue().version() <= upToVersion) {
                    blockCopy.put(entry.getKey(), entry.getValue());
                    remaining--;
                }
            }
        }
        return new Snapshot(accountCopy, blockCopy, consumeCopy);
    }

    public synchronized void acknowledge(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        snapshot.accounts().forEach((id, saved) -> dirtyAccounts.remove(id, saved));
        snapshot.blocks().forEach((position, saved) -> dirtyBlocks.remove(position, saved));
        snapshot.consumes().forEach((id, saved) -> dirtyConsumeReplays.remove(id, saved));
        pruneConsumeReplayCache(System.currentTimeMillis());
    }

    public synchronized ProvenanceMetrics provenanceMetrics() {
        HashMap<UUID, Integer> perWorld = new HashMap<>();
        artificial.forEach((world, positions) -> perWorld.put(world, positions.size()));
        double seconds = Math.max(0.001d, (System.nanoTime() - metricsStartNanos) / 1_000_000_000.0d);
        return new ProvenanceMetrics(seconds, blockCount, Map.copyOf(perWorld), provenanceMarks,
                provenanceUnmarks, provenanceMoves, largestProvenanceBatch);
    }

    public synchronized int playerCount() { return accounts.size(); }
    public synchronized long blockCount() { return blockCount; }
    public synchronized int consumeReplayCount() { return consumeReplays.size(); }
    public synchronized int dirtyAccounts() { return dirtyAccounts.size(); }
    public synchronized int dirtyBlocks() { return dirtyBlocks.size(); }
    public synchronized int dirtyConsumes() { return dirtyConsumeReplays.size(); }
    public synchronized int dirtyCount() { return dirtyAccounts.size() + dirtyBlocks.size() + dirtyConsumeReplays.size(); }
    public synchronized long revision() { return version; }
}
