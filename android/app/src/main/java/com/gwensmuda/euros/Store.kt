package com.gwensmuda.euros

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
 @Query("SELECT * FROM metadata") suspend fun allMetadata():List<Metadata>
 @Transaction suspend fun exportSnapshot()=ExportSnapshot(recordsIncludingDeleted(),pending(),allMetadata())
 @Query("SELECT * FROM records WHERE deleted=0") fun observe():Flow<List<Record>>
 @Query("SELECT * FROM outbox ORDER BY createdAt, expectedRevision, commandId") fun observePending():Flow<List<Pending>>
 @Query("SELECT * FROM outbox ORDER BY createdAt, expectedRevision, commandId") suspend fun pending():List<Pending>
 @Query("SELECT * FROM records WHERE type=:type AND id=:id") suspend fun record(type:String,id:String):Record?
 @Query("SELECT * FROM outbox WHERE type=:type AND id=:id ORDER BY createdAt, expectedRevision, commandId LIMIT 1") suspend fun pendingFor(type:String,id:String):Pending?
 @Query("SELECT * FROM outbox WHERE type=:type AND id=:id ORDER BY createdAt, expectedRevision, commandId") suspend fun pendingForEntity(type:String,id:String):List<Pending>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun put(record:Record)
 @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun enqueue(command:Pending)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun meta(value:Metadata)
 @Query("DELETE FROM metadata WHERE `key`=:key") suspend fun removeMeta(key:String)
 @Query("SELECT value FROM metadata WHERE `key`=:key") suspend fun meta(key:String):String?
 @Query("SELECT value FROM metadata WHERE `key`=:key") fun observeMeta(key:String):Flow<String?>
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
