package org.fitchfamily.android.dejavu

/*
*    DejaVu - A location provider backend for microG/UnifiedNlp
*
*    Copyright (C) 2017 Tod Fitch
*
*    This program is Free Software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as
*    published by the Free Software Foundation, either version 3 of the
*    License, or (at your option) any later version.
*
*    This program is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.util.Log
import java.util.HashSet

/**
 *
 * Created by tfitch on 9/1/17.
 */
/**
 * Interface to our on flash SQL database. Note that these methods are not
 * thread safe. However all access to the database is through the Cache object
 * which is thread safe.
 */
class Database(context: Context?) :
    SQLiteOpenHelper(context, NAME, null, VERSION) {
    private val database: SQLiteDatabase get() = writableDatabase
    private var withinTransaction = false
    private var updatesMade = false
    private var sqlSampleInsert: SQLiteStatement? = null
    private var sqlSampleUpdate: SQLiteStatement? = null
    private var sqlAPdrop: SQLiteStatement? = null

    override fun onCreate(db: SQLiteDatabase) {
        withinTransaction = false
        // Always create version 1 of database, then update the schema
        // in the same order it might occur "in the wild". Avoids having
        // to check to see if the table exists (may be old version)
        // or not (can be new version).
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SAMPLES (
                $COL_RFID STRING PRIMARY KEY,
                $COL_TYPE STRING,
                $COL_TRUST INTEGER,
                $COL_LAT REAL,
                $COL_LON REAL,
                $COL_RAD REAL,
                $COL_NOTE STRING
            );
        """.trimIndent()
        )
        onUpgrade(db, 1, VERSION)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) upGradeToVersion2(db)
        if (oldVersion < 3) upGradeToVersion3(db)
        if (oldVersion < 4) upGradeToVersion4(db)
    }

    private fun upGradeToVersion2(db: SQLiteDatabase) {
        if (DEBUG) Log.d(TAG, "upGradeToVersion2(): Entry")
        // Sqlite3 does not support dropping columns so we create a new table with our
        // current fields and copy the old data into it.
        with(db) {
            execSQL("BEGIN TRANSACTION;")
            execSQL("ALTER TABLE " + TABLE_SAMPLES + " RENAME TO " + TABLE_SAMPLES + "_old;")
            execSQL(
                ("CREATE TABLE IF NOT EXISTS " + TABLE_SAMPLES + "(" +
                        COL_RFID + " STRING PRIMARY KEY, " +
                        COL_TYPE + " STRING, " +
                        COL_TRUST + " INTEGER, " +
                        COL_LAT + " REAL, " +
                        COL_LON + " REAL, " +
                        COL_RAD_NS + " REAL, " +
                        COL_RAD_EW + " REAL, " +
                        COL_NOTE + " STRING);")
            )
            execSQL(
                ("INSERT INTO " + TABLE_SAMPLES + "(" +
                        COL_RFID + ", " +
                        COL_TYPE + ", " +
                        COL_TRUST + ", " +
                        COL_LAT + ", " +
                        COL_LON + ", " +
                        COL_RAD_NS + ", " +
                        COL_RAD_EW + ", " +
                        COL_NOTE +
                        ") SELECT " +
                        COL_RFID + ", " +
                        COL_TYPE + ", " +
                        COL_TRUST + ", " +
                        COL_LAT + ", " +
                        COL_LON + ", " +
                        COL_RAD + ", " +
                        COL_RAD + ", " +
                        COL_NOTE +
                        " FROM " + TABLE_SAMPLES + "_old;")
            )
            execSQL("DROP TABLE " + TABLE_SAMPLES + "_old;")
            execSQL("COMMIT;")
        }
    }

    private fun upGradeToVersion3(db: SQLiteDatabase) {
        if (DEBUG) Log.d(TAG, "upGradeToVersion3(): Entry")

        // We are changing our key field to a new text field that contains a hash of
        // of the ID and type. In addition, we are dealing with a Lint complaint about
        // using a string field where we ought to be using a text field.
        db.execSQL("BEGIN TRANSACTION;")
        db.execSQL(
            ("CREATE TABLE IF NOT EXISTS " + TABLE_SAMPLES + "_new (" +
                    COL_HASH + " TEXT PRIMARY KEY, " +
                    COL_RFID + " TEXT, " +
                    COL_TYPE + " TEXT, " +
                    COL_TRUST + " INTEGER, " +
                    COL_LAT + " REAL, " +
                    COL_LON + " REAL, " +
                    COL_RAD_NS + " REAL, " +
                    COL_RAD_EW + " REAL, " +
                    COL_NOTE + " TEXT);")
        )
        val insert = db.compileStatement(
            ("INSERT INTO " +
                    TABLE_SAMPLES + "_new(" +
                    COL_HASH + ", " +
                    COL_RFID + ", " +
                    COL_TYPE + ", " +
                    COL_TRUST + ", " +
                    COL_LAT + ", " +
                    COL_LON + ", " +
                    COL_RAD_NS + ", " +
                    COL_RAD_EW + ", " +
                    COL_NOTE + ") " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);")
        )
        val query = ("SELECT " +
                COL_RFID + "," + COL_TYPE + "," + COL_TRUST + "," + COL_LAT + "," + COL_LON + "," + COL_RAD_NS + "," + COL_RAD_EW + "," + COL_NOTE + " " +
                "FROM " + TABLE_SAMPLES + ";")
        val cursor = db.rawQuery(query, null)
        try {
            if (cursor!!.moveToFirst()) {
                do {
                    val rfId = cursor.getString(0)
                    var rftype = cursor.getString(1)
                    if ((rftype == "WLAN")) rftype = "WLAN_24GHZ"
                    val hash = rfId + rftype  // value doesn't matter, it's removed in next upgrade anyway

                    // Log.d(TAG,"upGradeToVersion2(): Updating '"+rfId.toString()+"'");
                    insert.bindString(1, hash)
                    insert.bindString(2, rfId)
                    insert.bindString(3, rftype)
                    insert.bindString(4, cursor.getString(2))
                    insert.bindString(5, cursor.getString(3))
                    insert.bindString(6, cursor.getString(4))
                    insert.bindString(7, cursor.getString(5))
                    insert.bindString(8, cursor.getString(6))
                    insert.bindString(9, cursor.getString(7))
                    insert.executeInsert()
                    insert.clearBindings()
                } while (cursor.moveToNext())
            }
        } finally {
            cursor?.close()
        }
        db.execSQL("DROP TABLE $TABLE_SAMPLES;")
        db.execSQL("ALTER TABLE ${TABLE_SAMPLES}_new RENAME TO $TABLE_SAMPLES;")
        db.execSQL("COMMIT;")
    }

    private fun upGradeToVersion4(db: SQLiteDatabase) {
        // another upgrade
        // type column is now ordinal of EmitterType (idea: save space, allow for range queries)
        // remove hash column
        //  mobile emitter IDs are already unique
        //  wifi emitters get wifi type prepended
        // add index on latitude/longitude/type to accelerate the bounding box + type queries
        db.execSQL("BEGIN TRANSACTION;")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${TABLE_SAMPLES}_new (
            $COL_RFID TEXT PRIMARY KEY NOT NULL,
            $COL_TYPE INTEGER NOT NULL,
            $COL_TRUST INTEGER NOT NULL,
            $COL_LAT REAL NOT NULL,
            $COL_LON REAL NOT NULL,
            $COL_RAD_NS REAL NOT NULL,
            $COL_RAD_EW REAL NOT NULL,
            $COL_NOTE TEXT
            );
        """.trimIndent()
        )
        db.execSQL("DROP INDEX IF EXISTS $SPATIAL_INDEX_SAMPLES;")
        // add 2.4 GHz WiFis
        db.execSQL("""
            INSERT INTO ${TABLE_SAMPLES}_new($COL_RFID, $COL_TYPE, $COL_TRUST, $COL_LAT, $COL_LON, $COL_RAD_NS, $COL_RAD_EW, $COL_NOTE)
            SELECT '${EmitterType.WLAN2}/' || $COL_RFID, ${EmitterType.WLAN2.ordinal}, $COL_TRUST, $COL_LAT, $COL_LON, $COL_RAD_NS, $COL_RAD_EW, $COL_NOTE
            FROM $TABLE_SAMPLES
            WHERE $COL_TYPE = 'WLAN_24GHZ';
        """.trimIndent()
        )
        // add 5 GHz WiFis
        db.execSQL("""
            INSERT INTO ${TABLE_SAMPLES}_new($COL_RFID, $COL_TYPE, $COL_TRUST, $COL_LAT, $COL_LON, $COL_RAD_NS, $COL_RAD_EW, $COL_NOTE)
            SELECT '${EmitterType.WLAN5}/' || $COL_RFID, ${EmitterType.WLAN5.ordinal}, $COL_TRUST, $COL_LAT, $COL_LON, $COL_RAD_NS, $COL_RAD_EW, $COL_NOTE
            FROM $TABLE_SAMPLES
            WHERE $COL_TYPE = 'WLAN_5GHZ';
        """.trimIndent()
        )
        // cell towers are already unique, but we need to split the types, as they may hav different characteristics
        for (emitterType in arrayOf(EmitterType.GSM, EmitterType.WCDMA, EmitterType.CDMA, EmitterType.LTE)) {
            db.execSQL("""
            INSERT INTO ${TABLE_SAMPLES}_new($COL_RFID, $COL_TYPE, $COL_TRUST, $COL_LAT, $COL_LON, $COL_RAD_NS, $COL_RAD_EW, $COL_NOTE)
            SELECT $COL_RFID, ${emitterType.ordinal}, $COL_TRUST, $COL_LAT, $COL_LON, $COL_RAD_NS, $COL_RAD_EW, $COL_NOTE
            FROM $TABLE_SAMPLES
            WHERE $COL_TYPE = 'MOBILE' AND $COL_RFID LIKE '${emitterType}%';
        """.trimIndent()
            )
        }
        db.execSQL("DROP TABLE $TABLE_SAMPLES;")
        db.execSQL("ALTER TABLE ${TABLE_SAMPLES}_new RENAME TO $TABLE_SAMPLES;")
        db.execSQL("CREATE INDEX $SPATIAL_INDEX_SAMPLES ON $TABLE_SAMPLES ($COL_LAT,$COL_LON,$COL_TYPE);")
        db.execSQL("COMMIT;")
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
    }

    /**
     * Start an update operation.
     *
     * We make sure we are not already in a transaction, make sure
     * our database is writeable, compile the insert, update and drop
     * statements that are likely to be used, etc. Then we actually
     * start the transaction on the underlying SQL database.
     */
    fun beginTransaction() {
        if (withinTransaction) {
            if (DEBUG) Log.d(TAG, "beginTransaction() - Already in a transaction?")
            return
        }
        withinTransaction = true
        updatesMade = false
        sqlSampleInsert = database.compileStatement(
            ("INSERT INTO " +
                    TABLE_SAMPLES + "(" +
                    COL_RFID + ", " +
                    COL_TYPE + ", " +
                    COL_TRUST + ", " +
                    COL_LAT + ", " +
                    COL_LON + ", " +
                    COL_RAD_NS + ", " +
                    COL_RAD_EW + ", " +
                    COL_NOTE + ") " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?);")
        )
        sqlSampleUpdate = database.compileStatement(
            ("UPDATE " +
                    TABLE_SAMPLES + " SET " +
                    COL_TRUST + "=?, " +
                    COL_LAT + "=?, " +
                    COL_LON + "=?, " +
                    COL_RAD_NS + "=?, " +
                    COL_RAD_EW + "=?, " +
                    COL_NOTE + "=? " +
                    "WHERE " + COL_RFID + "=?;")
        )
        sqlAPdrop = database.compileStatement(
            ("DELETE FROM " +
                    TABLE_SAMPLES +
                    " WHERE " + COL_RFID + "=?;")
        )
        database.beginTransaction()
    }

    /**
     * End a transaction. If we actually made any changes then we mark
     * the transaction as successful. Once marked as successful we
     * end the transaction with the underlying SQL database.
     */
    fun endTransaction() {
        if (!withinTransaction) {
            if (DEBUG) Log.d(TAG, "Asked to end transaction but we are not in one???")
        }
        if (updatesMade) {
            //Log.d(TAG,"endTransaction() - Setting transaction successful.");
            database.setTransactionSuccessful()
        }
        updatesMade = false
        database.endTransaction()
        withinTransaction = false
    }

    /**
     * Drop an RF emitter from the database.
     *
     * @param emitter The emitter to be dropped.
     */
    fun drop(emitter: RfEmitter) {
        if (DEBUG) Log.d(TAG, "Dropping " + emitter.logString + " from db")
        sqlAPdrop!!.bindString(1, emitter.uniqueId)
        sqlAPdrop!!.executeInsert()
        sqlAPdrop!!.clearBindings()
        updatesMade = true
    }

    /**
     * Insert a new RF emitter into the database.
     *
     * @param emitter The emitter to be added.
     */
    fun insert(emitter: RfEmitter) {
        if (DEBUG) Log.d(TAG, "Inserting " + emitter.logString + " into db")
        sqlSampleInsert!!.bindString(1, emitter.uniqueId)
        sqlSampleInsert!!.bindString(2, emitter.type.ordinal.toString())
        sqlSampleInsert!!.bindString(3, emitter.trust.toString())
        sqlSampleInsert!!.bindString(4, emitter.lat.toString())
        sqlSampleInsert!!.bindString(5, emitter.lon.toString())
        sqlSampleInsert!!.bindString(6, emitter.radiusNS.toString())
        sqlSampleInsert!!.bindString(7, emitter.radiusEW.toString())
        sqlSampleInsert!!.bindString(8, emitter.note)
        sqlSampleInsert!!.executeInsert()
        sqlSampleInsert!!.clearBindings()
        updatesMade = true
    }

    /**
     * Update information about an emitter already existing in the database
     *
     * @param emitter The emitter to be updated
     */
    fun update(emitter: RfEmitter) {
        if (DEBUG) Log.d(TAG, "Updating " + emitter.logString)

        // the data fields
        sqlSampleUpdate!!.bindString(1, emitter.trust.toString())
        sqlSampleUpdate!!.bindString(2, emitter.lat.toString())
        sqlSampleUpdate!!.bindString(3, emitter.lon.toString())
        sqlSampleUpdate!!.bindString(4, emitter.radiusNS.toString())
        sqlSampleUpdate!!.bindString(5, emitter.radiusEW.toString())
        sqlSampleUpdate!!.bindString(6, emitter.note)

        // the Where fields
        sqlSampleUpdate!!.bindString(7, emitter.uniqueId)
        sqlSampleUpdate!!.executeInsert()
        sqlSampleUpdate!!.clearBindings()
        updatesMade = true
    }

    /**
     * Return a list of all emitters of a specified type within a bounding box.
     *
     * @param rfType The type of emitter the caller is interested in
     * @param bb The lat,lon bounding box.
     * @return A collection of RF emitter identifications
     */
    fun getIds(rfType: EmitterType, bb: BoundingBox): HashSet<RfIdentification> {
        val result = HashSet<RfIdentification>()
        val query = """
            SELECT $COL_RFID FROM $TABLE_SAMPLES
            WHERE $COL_TYPE = ${rfType.ordinal}
            AND $COL_LAT BETWEEN ${bb.south} AND ${bb.north}
            AND $COL_LON BETWEEN ${bb.west} AND ${bb.east};
        """.trimIndent()

        database.rawQuery(query, null).use { cursor ->
            if (cursor!!.moveToFirst()) {
                do {
                    val e = getRfId(cursor.getString(0), rfType)
                    result.add(e)
                } while (cursor.moveToNext())
            }
        }
        if (DEBUG) Log.d(TAG, "getIds(bbox) returned ${result.size} $rfType emitters")
        return result
    }

    fun getEmitters(rfTypes: Collection<EmitterType>, bb: BoundingBox): HashSet<RfEmitter> {
        val result = HashSet<RfEmitter>()
        val query = """
            SELECT $COL_RFID,$COL_TYPE,$COL_TRUST,$COL_LAT,$COL_LON,$COL_RAD_NS,$COL_RAD_EW,$COL_NOTE FROM $TABLE_SAMPLES
            WHERE $COL_TYPE IN (${rfTypes.map { it.ordinal }.joinToString(",")})
            AND $COL_LAT BETWEEN ${bb.south} AND ${bb.north}
            AND $COL_LON BETWEEN ${bb.west} AND ${bb.east};
        """.trimIndent()

        database.rawQuery(query, null).use { cursor ->
            if (cursor!!.moveToFirst()) {
                do {
                    val info = EmitterInfo(
                        trust = cursor.getInt(2),
                        latitude = cursor.getDouble(3),
                        longitude = cursor.getDouble(4),
                        radius_ns = cursor.getDouble(5).toFloat(),
                        radius_ew = cursor.getDouble(6).toFloat(),
                        note = cursor.getString(7) ?: ""
                    )
                    val e = RfEmitter(getRfId(cursor.getString(0), EmitterType.values()[cursor.getInt(1)]), info)
                    result.add(e)
                } while (cursor.moveToNext())
            }
        }
        if (DEBUG) Log.d(TAG, "getEmitters(bbox) returned ${result.size} emitters")
        return result
    }

    private fun getRfId(dbId: String, type: EmitterType) =
        when (type) {
            EmitterType.WLAN2, EmitterType.WLAN5, EmitterType.WLAN6 -> RfIdentification(dbId.substringAfter('/'), type)
            else -> RfIdentification(dbId, type)
        }

    // get multiple emitters instead of querying one by one
    fun getEmitters(ids: Collection<RfIdentification>): List<RfEmitter> {
        if (ids.isEmpty()) return emptyList()
        val idString = ids.joinToString(",") { "'${it.uniqueId}'" }
        val query = ("SELECT " +
                COL_TYPE + ", " +
                COL_TRUST + ", " +
                COL_LAT + ", " +
                COL_LON + ", " +
                COL_RAD_NS + ", " +
                COL_RAD_EW + ", " +
                COL_NOTE + ", " +
                COL_RFID + " " +
                " FROM " + TABLE_SAMPLES +
                " WHERE " + COL_RFID + " IN (" + idString + ");")

        if (DEBUG) Log.d(TAG, "getEmitters(ids): $idString")
        val c = database.rawQuery(query, null)
        val emitters = mutableListOf<RfEmitter>()
        c.use { cursor ->
            if (cursor!!.moveToFirst()) {
                do {
                    val info = EmitterInfo(
                        trust = cursor.getInt(1),
                        latitude = cursor.getDouble(2),
                        longitude = cursor.getDouble(3),
                        radius_ns = cursor.getDouble(4).toFloat(),
                        radius_ew = cursor.getDouble(5).toFloat(),
                        note = cursor.getString(6) ?: ""
                    )
                    val result = RfEmitter(
                        getRfId(
                            cursor.getString(7),
                            EmitterType.values()[cursor.getInt(0)]
                        ), info)
                    emitters.add(result)
                } while (cursor.moveToNext())
            }
        }
        if (DEBUG) {
            if (ids.size != emitters.size)
                Log.d(TAG, "getEmitters(ids): not all emitters found, returning ${emitters.map { it.uniqueId }}")
        }
        return emitters
    }

    /**
     * Get all the information we have on an RF emitter
     *
     * @param identification The identification of the emitter caller wants
     * @return A emitter object with all the information we have. Or null if we have nothing.
     */
    fun getEmitter(identification: RfIdentification): RfEmitter? {
        val result: RfEmitter?
        val query = ("SELECT " +
                COL_TRUST + ", " +
                COL_LAT + ", " +
                COL_LON + ", " +
                COL_RAD_NS + ", " +
                COL_RAD_EW + ", " +
                COL_NOTE + " " +
                " FROM " + TABLE_SAMPLES +
                " WHERE " + COL_RFID + "='" + identification.uniqueId + "';")

        if (DEBUG) Log.d(TAG, "getEmitter(): $identification");
        database.rawQuery(query, null).use { cursor ->
            if (cursor!!.moveToFirst()) {
                val ei = EmitterInfo(
                    trust = cursor.getInt(0),
                    latitude = cursor.getDouble(1),
                    longitude = cursor.getDouble(2),
                    radius_ns = cursor.getDouble(3).toFloat(),
                    radius_ew = cursor.getDouble(4).toFloat(),
                    note = cursor.getString(5) ?: ""
                )
                result = RfEmitter(identification, ei)
            }
            else result = null
        }
        return result
    }

    companion object {
        private const val TAG = "DejaVu DB"
        private val DEBUG = BuildConfig.DEBUG
        private const val VERSION = 4
        private const val NAME = "rf.db"
        private const val TABLE_SAMPLES = "emitters"
        private const val SPATIAL_INDEX_SAMPLES = "emitters_index"
        private const val COL_HASH = "rfHash" // v3 of database, removed in v4
        private const val COL_TYPE = "rfType"
        private const val COL_RFID = "rfID"
        private const val COL_TRUST = "trust"
        private const val COL_LAT = "latitude"
        private const val COL_LON = "longitude"
        private const val COL_RAD = "radius" // v1 of database
        private const val COL_RAD_NS = "radius_ns" // v2 of database
        private const val COL_RAD_EW = "radius_ew" // v2 of database
        private const val COL_NOTE = "note"
    }
}

class EmitterInfo(
    val trust: Int = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radius_ns: Float = 0f,
    val radius_ew: Float = 0f,
    val note: String? = ""
)
