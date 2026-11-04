package com.gwensmuda.euros

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

// Room entities, queries and migrations. Keep schema changes explicit and versioned.
@Entity(tableName="records", primaryKeys=["type", "id"])
data class Record(val type:String, val id:String, val revision:Long, val payload:String, val deleted:Boolean=false)
@Entity(tableName="outbox")
data class Pending(@PrimaryKey val commandId:String, val type:String, val id:String, val expectedRevision:Long, val payload:String, val createdAt:Long=System.currentTimeMillis(), val error:String?=null, @ColumnInfo(defaultValue="'upsert'") val operation:String="upsert")
@Entity(tableName="metadata")
data class Metadata(@PrimaryKey val key:String, val value:String)
data class RestoreResult(val imported:Int,val skipped:Int,val localImported:Int=0,val localSkipped:Int=0)
data class ExportSnapshot(val records:List<Record>,val pending:List<Pending>,val metadata:List<Metadata>)
@Dao interface EurosDao {
 @Query("SELECT * FROM records") suspend fun recordsIncludingDeleted():List<Record>
 @Query("SELECT * FROM metadata") suspend fun rawMetadata():List<Metadata>
 @Transaction suspend fun allMetadata():List<Metadata> {
  val rows=rawMetadata()
  val index=rows.associate{it.key to it.value}
  return rows.filterNot{it.key.startsWith(HealthMetadataChunks.PREFIX)}.map{row->
   if(row.key==HealthImport.KEY) row.copy(value=HealthMetadataChunks.assemble(row.value){index[it]}) else row
  }
 }
 @Transaction suspend fun exportSnapshot()=ExportSnapshot(recordsIncludingDeleted(),pending(),allMetadata())
 @Query("SELECT * FROM records WHERE type='food_entries'") fun observeFoodIncludingDeleted():Flow<List<Record>>
 @Query("SELECT * FROM records WHERE deleted=0") fun observe():Flow<List<Record>>
 @Query("SELECT * FROM outbox ORDER BY createdAt, expectedRevision, commandId") fun observePending():Flow<List<Pending>>
 @Query("SELECT * FROM outbox ORDER BY createdAt, expectedRevision, commandId") suspend fun pending():List<Pending>
 @Query("SELECT * FROM records WHERE type=:type AND id=:id") suspend fun record(type:String,id:String):Record?
 @Query("SELECT * FROM outbox WHERE type=:type AND id=:id ORDER BY createdAt, expectedRevision, commandId LIMIT 1") suspend fun pendingFor(type:String,id:String):Pending?
 @Query("SELECT * FROM outbox WHERE type=:type AND id=:id ORDER BY createdAt, expectedRevision, commandId") suspend fun pendingForEntity(type:String,id:String):List<Pending>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(record:Record)
 @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun enqueue(command:Pending)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putRawMeta(value:Metadata)
 @Query("DELETE FROM metadata WHERE `key`=:key") suspend fun removeRawMeta(key:String)
 @Query("DELETE FROM metadata WHERE `key` LIKE 'health_import_v1:chunk:%'") suspend fun removeHealthChunks()
 @Query("SELECT value FROM metadata WHERE `key`=:key") suspend fun rawMeta(key:String):String?
 @Query("SELECT value FROM metadata WHERE `key`=:key") fun observeRawMeta(key:String):Flow<String?>
 @Transaction suspend fun meta(value:Metadata) {
  if(value.key!=HealthImport.KEY){putRawMeta(value);return}
  val pieces=HealthMetadataChunks.split(value.value)
  removeHealthChunks()
  pieces.drop(1).forEach{putRawMeta(it)}
  putRawMeta(pieces.first())
 }
 @Transaction suspend fun removeMeta(key:String) {
  removeRawMeta(key)
  if(key==HealthImport.KEY)removeHealthChunks()
 }
 @Transaction suspend fun meta(key:String):String? {
  val raw=rawMeta(key) ?: return null
  if(key!=HealthImport.KEY)return raw
  return HealthMetadataChunks.assemble(raw){rawMeta(it)}
 }
 fun observeMeta(key:String):Flow<String?> = observeRawMeta(key).map { raw ->
  if(key==HealthImport.KEY && raw!=null && HealthMetadataChunks.isManifest(raw))meta(key) else raw
 }
 @Query("DELETE FROM outbox WHERE commandId=:id") suspend fun acknowledge(id:String)
 @Query("UPDATE outbox SET error=:error WHERE commandId=:id") suspend fun error(id:String,error:String)
}
@Database(entities=[Record::class,Pending::class,Metadata::class],version=2,exportSchema=true)
abstract class EurosDatabase:RoomDatabase() { abstract fun dao():EurosDao
 companion object {
  val MIGRATION_1_2=object:androidx.room.migration.Migration(1,2) {
   override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("ALTER TABLE outbox ADD COLUMN operation TEXT NOT NULL DEFAULT 'upsert'") }
  }
  fun open(context:Context)=Room.databaseBuilder(context,EurosDatabase::class.java,"euros.db").addMigrations(MIGRATION_1_2).build()
 }
}


/** Store large compressed history below CursorWindow's per-row ceiling, atomically. */
object HealthMetadataChunks {
 const val PREFIX="health_import_v1:chunk:"
 const val SIZE=500_000
 private const val ENCODING="metadata-chunks-v1"
 fun isManifest(raw:String)=raw.length<200 && runCatching{JSONObject(raw).optString("encoding")==ENCODING}.getOrDefault(false)
 fun split(raw:String):List<Metadata> {
  if(raw.length<=SIZE)return listOf(Metadata(HealthImport.KEY,raw))
  val parts=raw.chunked(SIZE)
  require(parts.size<=32){"Imported history exceeds local storage limit"}
  val header=JSONObject().put("encoding",ENCODING).put("count",parts.size).put("length",raw.length).toString()
  return listOf(Metadata(HealthImport.KEY,header))+parts.mapIndexed{i,part->Metadata(PREFIX+i,part)}
 }
 suspend fun assemble(raw:String,read:suspend (String)->String?):String {
  if(!isManifest(raw))return raw
  val header=JSONObject(raw);val count=header.getInt("count");require(count in 1..32)
  return buildString { repeat(count){append(read(PREFIX+it) ?: error("Tracker history chunk missing"))} }
   .also{check(it.length==header.getInt("length")){"Tracker history length mismatch"}}
 }
}
