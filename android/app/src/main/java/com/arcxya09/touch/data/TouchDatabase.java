package com.arcxya09.touch.data;

import androidx.annotation.NonNull;
import androidx.room.*;
import java.util.List;

@Database(entities = {TouchDatabase.Item.class, TouchDatabase.Outbox.class}, version = 2, exportSchema = false)
public abstract class TouchDatabase extends RoomDatabase {
    public abstract Cache cache();

    @Entity(tableName = "items", primaryKeys = {"kind", "id"}, indices = {@Index(value={"kind", "conversationId", "seq"}), @Index(value={"kind", "createdAt"}), @Index(value={"attachmentId"})})
    public static class Item {
        @NonNull public String kind;
        @NonNull public String id;
        @NonNull public String json;
        @NonNull @ColumnInfo(defaultValue="''") public String conversationId = "";
        @ColumnInfo(defaultValue="0") public long seq;
        @ColumnInfo(defaultValue="0") public long createdAt;
        @NonNull @ColumnInfo(defaultValue="''") public String attachmentId = "";
        public Item(@NonNull String kind, @NonNull String id, @NonNull String json) {
            this.kind = kind; this.id = id; this.json = json;
            if (kind.equals("message") || kind.equals("attachment") || kind.equals("draft")) {
                try {
                    org.json.JSONObject value = new org.json.JSONObject(json);
                    conversationId = value.optString("conversation_id", "");
                    seq = value.optLong("seq");
                    createdAt = value.optLong("created_at", value.optLong("local_created_at"));
                    org.json.JSONObject attachment = value.optJSONObject("attachment");
                    attachmentId = attachment == null ? "" : attachment.optString("id");
                } catch (org.json.JSONException e) { throw new IllegalArgumentException("Invalid cache record", e); }
            }
        }
    }
    @Entity(tableName = "outbox", indices = {@Index(value={"conversationId", "createdAt"})})
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
        @Query("SELECT EXISTS(SELECT 1 FROM items) + EXISTS(SELECT 1 FROM outbox)") kotlinx.coroutines.flow.Flow<Integer> changes();
        @Query("SELECT * FROM items WHERE kind='message' AND conversationId=:cid AND seq<:before AND createdAt>:floor ORDER BY seq DESC LIMIT 50") List<Item> page(String cid, long before, long floor);
        @Query("SELECT * FROM items WHERE kind='message' AND conversationId=:cid AND seq BETWEEN :first AND :last AND createdAt>:floor ORDER BY seq LIMIT 50") List<Item> window(String cid, long first, long last, long floor);
        @Query("SELECT * FROM items WHERE kind='message' AND conversationId=:cid AND seq<:before AND createdAt>:floor AND (:showRecalled OR json_extract(json, '$.kind')!='recalled') ORDER BY seq DESC LIMIT 50") List<Item> visiblePage(String cid, long before, long floor, boolean showRecalled);
        @Query("SELECT * FROM items WHERE kind='message' AND conversationId=:cid AND seq BETWEEN :first AND :last AND createdAt>:floor AND (:showRecalled OR json_extract(json, '$.kind')!='recalled') ORDER BY seq LIMIT 50") List<Item> visibleWindow(String cid, long first, long last, long floor, boolean showRecalled);
        @Query("SELECT * FROM items WHERE kind=:kind AND createdAt<=:floor LIMIT 500") List<Item> expired(String kind, long floor);
        @Query("SELECT * FROM items WHERE kind='message' AND conversationId=:cid AND seq<=:through") List<Item> cleared(String cid, long through);
        @Query("SELECT attachmentId FROM items WHERE attachmentId!=''") List<String> referencedFiles();
        @Query("SELECT * FROM outbox WHERE conversationId=:cid AND createdAt>:floor ORDER BY createdAt") List<Outbox> pendingFor(String cid, long floor);
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
