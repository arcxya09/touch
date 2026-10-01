package com.arcxya09.touch.data;

import androidx.annotation.NonNull;
import androidx.room.*;
import java.util.List;

@Database(entities = {TouchDatabase.Item.class, TouchDatabase.Outbox.class, TouchDatabase.VisibilityMark.class,
        TouchDatabase.LocalCutoff.class, TouchDatabase.FileDeletion.class}, version = 3, exportSchema = true)
public abstract class TouchDatabase extends RoomDatabase {
    public abstract Cache cache();

    /** Device-local rules deliberately survive logout and cache replacement. */
    @Entity(tableName = "visibility_marks", primaryKeys = {"ownerId", "messageId"})
    public static class VisibilityMark {
        @NonNull public String ownerId;
        @NonNull public String messageId;
        public VisibilityMark(@NonNull String ownerId, @NonNull String messageId) {
            this.ownerId = ownerId; this.messageId = messageId;
        }
    }
    @Entity(tableName = "local_cutoffs")
    public static class LocalCutoff {
        @PrimaryKey @NonNull public String ownerId;
        public long throughTime;
        public LocalCutoff(@NonNull String ownerId, long throughTime) {
            this.ownerId = ownerId; this.throughTime = throughTime;
        }
    }
    @Entity(tableName = "file_deletions")
    public static class FileDeletion {
        @PrimaryKey @NonNull public String id;
        public FileDeletion(@NonNull String id) { this.id = id; }
    }

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
        @Insert(onConflict = OnConflictStrategy.IGNORE) void hide(VisibilityMark mark);
        @Query("SELECT EXISTS(SELECT 1 FROM visibility_marks WHERE ownerId=:owner AND messageId=:id)") boolean hidden(String owner, String id);
        @Query("INSERT OR IGNORE INTO visibility_marks(ownerId,messageId) SELECT :owner,id FROM items WHERE kind='message' AND createdAt>:floor") void hideRecent(String owner, long floor);
        @Query("SELECT * FROM local_cutoffs WHERE ownerId=:owner") LocalCutoff localCutoff(String owner);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void cutoff(LocalCutoff cutoff);
        @Insert(onConflict = OnConflictStrategy.IGNORE) void deleteFile(FileDeletion file);
        @Query("SELECT * FROM file_deletions LIMIT 100") List<FileDeletion> fileDeletions();
        @Query("SELECT EXISTS(SELECT 1 FROM file_deletions WHERE id=:id)") boolean fileDeletionPending(String id);
        @Query("DELETE FROM file_deletions WHERE id=:id") void fileDeleted(String id);
        @Query("SELECT MIN(createdAt) FROM (SELECT createdAt FROM items WHERE kind IN ('message','draft','attachment') AND createdAt>0 UNION ALL SELECT createdAt FROM outbox WHERE createdAt>0)") Long earliestContentTime();
        @Query("SELECT EXISTS(SELECT 1 FROM items) + EXISTS(SELECT 1 FROM outbox)") kotlinx.coroutines.flow.Flow<Integer> changes();
        @Query("SELECT EXISTS(SELECT 1 FROM items WHERE kind IN ('message','draft','attachment')) OR EXISTS(SELECT 1 FROM outbox)") boolean hasContent();
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
        @Query("SELECT * FROM outbox WHERE createdAt<=:floor LIMIT 100") List<Outbox> expiredPending(long floor);
        @Query("DELETE FROM outbox WHERE id=:id") void removePending(String id);
        @Query("DELETE FROM outbox") void clearPending();
    }
}
