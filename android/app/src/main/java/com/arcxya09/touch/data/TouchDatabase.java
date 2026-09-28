package com.arcxya09.touch.data;

import androidx.annotation.NonNull;
import androidx.room.*;
import java.util.List;

@Database(entities = {TouchDatabase.Item.class, TouchDatabase.Outbox.class}, version = 1, exportSchema = false)
public abstract class TouchDatabase extends RoomDatabase {
    public abstract Cache cache();

    @Entity(tableName = "items", primaryKeys = {"kind", "id"})
    public static class Item {
        @NonNull public String kind;
        @NonNull public String id;
        @NonNull public String json;
        public Item(@NonNull String kind, @NonNull String id, @NonNull String json) {
            this.kind = kind; this.id = id; this.json = json;
        }
    }
    @Entity(tableName = "outbox")
    public static class Outbox {
        @PrimaryKey @NonNull public String id;
        @NonNull public String conversationId;
        @NonNull public String body;
        public long createdAt;
        public Outbox(@NonNull String id, @NonNull String conversationId, @NonNull String body, long createdAt) {
            this.id = id; this.conversationId = conversationId; this.body = body; this.createdAt = createdAt;
        }
    }
    @Dao public interface Cache {
        @Query("SELECT * FROM items WHERE kind=:kind") List<Item> items(String kind);
        @Query("SELECT * FROM items WHERE kind=:kind AND id=:id LIMIT 1") Item get(String kind, String id);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void put(Item item);
        @Query("DELETE FROM items WHERE kind=:kind") void removeKind(String kind);
        @Query("DELETE FROM items WHERE kind=:kind AND id=:id") void remove(String kind, String id);
        @Query("DELETE FROM items") void clear();
        @Insert(onConflict = OnConflictStrategy.REPLACE) void pending(Outbox item);
        @Query("SELECT * FROM outbox ORDER BY createdAt") List<Outbox> pendingItems();
        @Query("DELETE FROM outbox WHERE id=:id") void removePending(String id);
        @Query("DELETE FROM outbox") void clearPending();
    }
}
