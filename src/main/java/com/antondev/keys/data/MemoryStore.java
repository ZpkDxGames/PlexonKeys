package com.antondev.keys.data;

import com.antondev.keys.model.KeyTier;
import java.util.*;

/** Memory-only operations. The saver receives immutable, versioned deltas; no Bukkit objects cross threads. */
public final class MemoryStore {
    public static final long HARD_LIMIT = 1_000_000_000L;
    public record Account(UUID player, String name, long basic, long rare, long epic, long legendary) {
        public long amount(KeyTier tier) {
            return switch (tier) { case BASIC -> basic; case RARE -> rare; case EPIC -> epic; case LEGENDARY -> legendary; };
        }
    }
    public record Position(UUID world, long packed) {
        public static Position of(UUID world, int x, int y, int z) {
            if (x < -33_554_432 || x > 33_554_431 || z < -33_554_432 || z > 33_554_431 || y < -2048 || y > 2047)
                throw new IllegalArgumentException("Block coordinate exceeds Minecraft's packed position range");
            return new Position(world, ((long) x & 0x3ffffff) << 38 | ((long) z & 0x3ffffff) << 12 | (y & 0xfff));
        }
    }
    public record Versioned<T>(long version, T value) {}
    public record Snapshot(Map<UUID, Versioned<Account>> accounts, Map<Position, Versioned<Boolean>> blocks) {
        public Snapshot { accounts = Map.copyOf(accounts); blocks = Map.copyOf(blocks); }
        public boolean empty() { return accounts.isEmpty() && blocks.isEmpty(); }
    }
    private final Map<UUID, Account> accounts = new HashMap<>();
    private final Map<UUID, Set<Long>> artificial = new HashMap<>();
    private final Map<UUID, Versioned<Account>> dirtyAccounts = new HashMap<>();
    private final Map<Position, Versioned<Boolean>> dirtyBlocks = new HashMap<>();
    private long version;
    private long blockCount;

    public synchronized void loadAccount(Account account) {
        for (KeyTier tier : KeyTier.values()) validate(account.amount(tier));
        accounts.put(account.player(), account);
    }
    public synchronized void loadBlock(Position position) {
        if (artificial.computeIfAbsent(position.world(), ignored -> new HashSet<>()).add(position.packed())) blockCount++;
    }
    public synchronized Account account(UUID player) {
        Account account = accounts.get(player);
        return account != null ? account : new Account(player, "", 0, 0, 0, 0);
    }
    public synchronized void remember(UUID player, String name) {
        Account old = account(player);
        if (!Objects.equals(old.name(), name)) put(new Account(player, name, old.basic(), old.rare(), old.epic(), old.legendary()));
    }
    public synchronized Optional<UUID> findPlayer(String input) {
        try { return Optional.of(UUID.fromString(input)); } catch (IllegalArgumentException ignored) { }
        return accounts.values().stream().filter(a -> a.name().equalsIgnoreCase(input)).map(Account::player).findFirst();
    }
    public synchronized List<String> names() { return accounts.values().stream().map(Account::name).filter(s -> !s.isEmpty()).toList(); }
    public synchronized long balance(UUID player, KeyTier tier) { return account(player).amount(tier); }
    public synchronized long credit(UUID player, KeyTier tier, long requested, long cap) {
        validate(requested); validate(cap);
        long old = balance(player, tier);
        long added = Math.min(requested, Math.max(0, cap - old));
        if (added > 0) set(player, tier, old + added);
        return added;
    }
    public synchronized void set(UUID player, KeyTier tier, long amount) {
        validate(amount);
        Account a = account(player);
        put(new Account(player, a.name(), tier == KeyTier.BASIC ? amount : a.basic(),
                tier == KeyTier.RARE ? amount : a.rare(), tier == KeyTier.EPIC ? amount : a.epic(),
                tier == KeyTier.LEGENDARY ? amount : a.legendary()));
    }
    public synchronized boolean debit(UUID player, Map<KeyTier, Long> amounts) {
        for (var entry : amounts.entrySet()) {
            validate(entry.getValue());
            if (balance(player, entry.getKey()) < entry.getValue()) return false;
        }
        for (var entry : amounts.entrySet()) if (entry.getValue() > 0) set(player, entry.getKey(), balance(player, entry.getKey()) - entry.getValue());
        return true;
    }
    private void put(Account account) {
        accounts.put(account.player(), account);
        dirtyAccounts.put(account.player(), new Versioned<>(++version, account));
    }
    private static void validate(long amount) {
        if (amount < 0 || amount > HARD_LIMIT) throw new IllegalArgumentException("Amount must be between 0 and " + HARD_LIMIT);
    }
    public synchronized boolean artificial(Position position) {
        Set<Long> set = artificial.get(position.world());
        return set != null && set.contains(position.packed());
    }
    public synchronized void mark(Position position) {
        if (artificial.computeIfAbsent(position.world(), ignored -> new HashSet<>()).add(position.packed())) {
            blockCount++;
            dirtyBlocks.put(position, new Versioned<>(++version, true));
        }
    }
    public synchronized boolean unmark(Position position) {
        Set<Long> set = artificial.get(position.world());
        if (set == null || !set.remove(position.packed())) return false;
        if (set.isEmpty()) artificial.remove(position.world());
        blockCount--;
        dirtyBlocks.put(position, new Versioned<>(++version, false));
        return true;
    }
    /** Snapshot all sources before clearing: adjacent moved blocks must not overwrite each other's provenance. */
    public synchronized void move(Map<Position, Position> moves) {
        Set<Position> marked = new HashSet<>();
        for (var entry : moves.entrySet()) if (artificial(entry.getKey())) marked.add(entry.getValue());
        moves.keySet().forEach(this::unmark);
        moves.values().forEach(this::unmark);
        marked.forEach(this::mark);
    }
    public synchronized Snapshot snapshot() { return new Snapshot(dirtyAccounts, dirtyBlocks); }
    public synchronized void acknowledge(Snapshot snapshot) {
        snapshot.accounts().forEach((id, saved) -> dirtyAccounts.remove(id, saved));
        snapshot.blocks().forEach((position, saved) -> dirtyBlocks.remove(position, saved));
    }
    public synchronized int playerCount() { return accounts.size(); }
    public synchronized long blockCount() { return blockCount; }
    public synchronized int dirtyCount() { return dirtyAccounts.size() + dirtyBlocks.size(); }
    public synchronized long revision() { return version; }
}
