/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.sql.SQLException

class DatabaseMigration37To38Test {

    /**
     * Adapts a standard JDBC connection to Room's androidx.sqlite.SQLiteConnection interface,
     * allowing the actual generated Room AutoMigration class to run against a real SQLite database.
     */
    private class JdbcSQLiteConnectionAdapter(private val conn: java.sql.Connection) : SQLiteConnection {
        override fun prepare(sql: String): SQLiteStatement {
            val stmt = conn.prepareStatement(sql)
            return object : SQLiteStatement {
                override fun bindBlob(index: Int, value: ByteArray) {
                    stmt.setBytes(index, value)
                }

                override fun bindDouble(index: Int, value: Double) {
                    stmt.setDouble(index, value)
                }

                override fun bindLong(index: Int, value: Long) {
                    stmt.setLong(index, value)
                }

                override fun bindNull(index: Int) {
                    stmt.setNull(index, java.sql.Types.NULL)
                }

                override fun bindText(index: Int, value: String) {
                    stmt.setString(index, value)
                }

                override fun clearBindings() {
                    stmt.clearParameters()
                }

                override fun close() {
                    stmt.close()
                }

                override fun step(): Boolean {
                    return stmt.execute()
                }

                override fun getBlob(index: Int): ByteArray {
                    return stmt.resultSet.getBytes(index + 1)
                }

                override fun getDouble(index: Int): Double {
                    return stmt.resultSet.getDouble(index + 1)
                }

                override fun getLong(index: Int): Long {
                    return stmt.resultSet.getLong(index + 1)
                }

                override fun getText(index: Int): String {
                    return stmt.resultSet.getString(index + 1)
                }

                override fun isNull(index: Int): Boolean {
                    stmt.resultSet.getObject(index + 1)
                    return stmt.resultSet.wasNull()
                }

                override fun getColumnCount(): Int {
                    return stmt.resultSet?.metaData?.columnCount ?: 0
                }

                override fun getColumnName(index: Int): String {
                    return stmt.resultSet.metaData.getColumnName(index + 1)
                }

                override fun getColumnType(index: Int): Int {
                    return 3 // SQLite TEXT data type
                }

                override fun reset() {}
            }
        }

        override fun close() {
            conn.close()
        }
    }

    @Test
    fun testRealDatabaseMigrationFromV37FixtureToV38() {
        // Step 1: Connect to an in-memory SQLite database
        val jdbcConn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val adapter = JdbcSQLiteConnectionAdapter(jdbcConn)

        try {
            // Step 2: Load exported v37 Room schema and execute table creation DDL
            val schemasDir = File("schemas/com.rift.music.db.InternalDatabase").takeIf { it.exists() }
                ?: File("app/schemas/com.rift.music.db.InternalDatabase").takeIf { it.exists() }
                ?: File("schemas/com.beatwave.music.db.InternalDatabase").takeIf { it.exists() }
                ?: File("app/schemas/com.beatwave.music.db.InternalDatabase")

            val schema37File = File(schemasDir, "37.json")
            assertTrue("37.json must exist", schema37File.exists())

            val json37 = Json.parseToJsonElement(schema37File.readText()).jsonObject
            val entities37 = json37["database"]!!.jsonObject["entities"]!!.jsonArray

            val stmt = jdbcConn.createStatement()

            // Create all 18 tables from version 37 schema
            for (entity in entities37) {
                val createSql = entity.jsonObject["createSql"]!!.jsonPrimitive.content
                stmt.execute(createSql.replace("\${TABLE_NAME}", entity.jsonObject["tableName"]!!.jsonPrimitive.content))

                val indices = entity.jsonObject["indices"]?.jsonArray
                indices?.forEach { idx ->
                    val idxSql = idx.jsonObject["createSql"]!!.jsonPrimitive.content
                    stmt.execute(idxSql.replace("\${TABLE_NAME}", entity.jsonObject["tableName"]!!.jsonPrimitive.content))
                }
            }

            // Create v37 views
            val views37 = json37["database"]!!.jsonObject["views"]?.jsonArray
            views37?.forEach { v ->
                val vSql = v.jsonObject["createSql"]!!.jsonPrimitive.content
                stmt.execute(vSql.replace("\${VIEW_NAME}", v.jsonObject["viewName"]!!.jsonPrimitive.content))
            }

            // Step 3: Insert representative version-37 user records
            stmt.executeUpdate(
                "INSERT INTO song (id, title, duration, explicit, liked, totalPlayTime, isLocal, lyricsOffset, romanizeLyrics, isDownloaded, isUploaded, isVideo) " +
                        "VALUES ('song_weeknd_1', 'Blinding Lights', 200, 0, 0, 0, 0, 0, 0, 0, 0, 0)"
            )
            stmt.executeUpdate(
                "INSERT INTO artist (id, name, lastUpdateTime, isLocal) " +
                        "VALUES ('artist_weeknd', 'The Weeknd', '2024-01-01T00:00:00', 0)"
            )
            stmt.executeUpdate(
                "INSERT INTO playlist (id, name, isEditable, isLocal, isAutoSync) " +
                        "VALUES ('playlist_pop', 'Pop Hits', 1, 0, 0)"
            )
            stmt.executeUpdate(
                "INSERT INTO playlist_song_map (id, playlistId, songId, position) " +
                        "VALUES (1, 'playlist_pop', 'song_weeknd_1', 0)"
            )
            stmt.executeUpdate(
                "INSERT INTO event (id, songId, timestamp, playTime) " +
                        "VALUES (1, 'song_weeknd_1', 1700000000, 200)"
            )

            // Step 4: Run the actual compiled Room AutoMigration class for 37 -> 38
            val migration = InternalDatabase_AutoMigration_37_38_Impl()
            assertEquals(37, migration.startVersion)
            assertEquals(38, migration.endVersion)

            // Execute migration on the real SQLite database
            migration.migrate(adapter)

            // Step 5: Verify all existing v37 records survived without alteration
            val songRs = stmt.executeQuery("SELECT id, title, duration FROM song WHERE id = 'song_weeknd_1'")
            assertTrue("Song record must survive migration", songRs.next())
            assertEquals("Blinding Lights", songRs.getString("title"))
            assertEquals(200, songRs.getInt("duration"))
            songRs.close()

            val artistRs = stmt.executeQuery("SELECT id, name FROM artist WHERE id = 'artist_weeknd'")
            assertTrue("Artist record must survive migration", artistRs.next())
            assertEquals("The Weeknd", artistRs.getString("name"))
            artistRs.close()

            val playlistMapRs = stmt.executeQuery("SELECT playlistId, songId, position FROM playlist_song_map WHERE playlistId = 'playlist_pop'")
            assertTrue("PlaylistSongMap record must survive migration", playlistMapRs.next())
            assertEquals("song_weeknd_1", playlistMapRs.getString("songId"))
            assertEquals(0, playlistMapRs.getInt("position"))
            playlistMapRs.close()

            val eventRs = stmt.executeQuery("SELECT songId, timestamp FROM event WHERE songId = 'song_weeknd_1'")
            assertTrue("Playback event record must survive migration", eventRs.next())
            assertEquals(1700000000L, eventRs.getLong("timestamp"))
            eventRs.close()

            // Step 6: Verify the newly created disliked_song table functions correctly
            val countRsBefore = stmt.executeQuery("SELECT COUNT(*) FROM disliked_song")
            assertTrue(countRsBefore.next())
            assertEquals(0, countRsBefore.getInt(1))
            countRsBefore.close()

            // Insert a new record into disliked_song
            stmt.executeUpdate(
                "INSERT INTO disliked_song (id, title, artist, album, duration, thumbnailUrl, normalizedTitle, normalizedArtist, dislikedAt) " +
                        "VALUES ('disliked_1', 'Bad Song', 'Bad Artist', 'Bad Album', 180, 'https://art.url', 'bad song', 'bad artist', 1700000100)"
            )

            val dislikedRs = stmt.executeQuery("SELECT id, title, normalizedTitle, normalizedArtist FROM disliked_song WHERE id = 'disliked_1'")
            assertTrue("Disliked song must be readable from disliked_song table", dislikedRs.next())
            assertEquals("Bad Song", dislikedRs.getString("title"))
            assertEquals("bad song", dislikedRs.getString("normalizedTitle"))
            assertEquals("bad artist", dislikedRs.getString("normalizedArtist"))
            dislikedRs.close()

            // Step 7: Verify constraints and indexes
            // Primary key conflict: inserting duplicate ID must fail
            try {
                stmt.executeUpdate(
                    "INSERT INTO disliked_song (id, title, artist, album, duration, thumbnailUrl, normalizedTitle, normalizedArtist, dislikedAt) " +
                            "VALUES ('disliked_1', 'Another Title', 'Artist', NULL, 180, NULL, 'another title', 'artist', 1700000200)"
                )
                fail("Inserting duplicate primary key into disliked_song must throw an SQLite constraint exception")
            } catch (e: SQLException) {
                assertTrue("Expected primary key constraint violation", e.message?.contains("constraint") == true || e.message?.contains("UNIQUE") == true)
            }

            // Not-null constraint: inserting null title must fail
            try {
                stmt.executeUpdate(
                    "INSERT INTO disliked_song (id, title, artist, album, duration, thumbnailUrl, normalizedTitle, normalizedArtist, dislikedAt) " +
                            "VALUES ('disliked_null', NULL, 'Artist', NULL, 180, NULL, 'null title', 'artist', 1700000200)"
                )
                fail("Inserting null title into disliked_song must fail not-null constraint")
            } catch (e: SQLException) {
                assertTrue("Expected NOT NULL constraint violation", e.message?.contains("NOT NULL") == true || e.message?.contains("constraint") == true)
            }

            // Verify index index_disliked_song_normalizedTitle_normalizedArtist exists
            val indexRs = stmt.executeQuery("PRAGMA index_list('disliked_song')")
            var foundIndex = false
            while (indexRs.next()) {
                val indexName = indexRs.getString("name")
                if (indexName == "index_disliked_song_normalizedTitle_normalizedArtist") {
                    foundIndex = true
                    break
                }
            }
            indexRs.close()
            assertTrue("index_disliked_song_normalizedTitle_normalizedArtist must exist on disliked_song", foundIndex)

            stmt.close()
        } finally {
            adapter.close()
        }
    }

    @Test
    fun testSchemaExport37And38Integrity() {
        val schemasDir = File("schemas/com.rift.music.db.InternalDatabase").takeIf { it.exists() }
                ?: File("app/schemas/com.rift.music.db.InternalDatabase").takeIf { it.exists() }
                ?: File("schemas/com.beatwave.music.db.InternalDatabase").takeIf { it.exists() }
                ?: File("app/schemas/com.beatwave.music.db.InternalDatabase")

        assertTrue("Schemas directory must exist", schemasDir.exists())

        val schema37File = File(schemasDir, "37.json")
        val schema38File = File(schemasDir, "38.json")

        val json37 = Json.parseToJsonElement(schema37File.readText(Charsets.UTF_8)).jsonObject
        val json38 = Json.parseToJsonElement(schema38File.readText(Charsets.UTF_8)).jsonObject

        val db37 = json37["database"]!!.jsonObject
        val db38 = json38["database"]!!.jsonObject

        assertEquals(37, db37["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(38, db38["version"]!!.jsonPrimitive.content.toInt())

        val entities37 = db37["entities"]!!.jsonArray
        val entities38 = db38["entities"]!!.jsonArray

        val tables37 = entities37.associateBy { it.jsonObject["tableName"]!!.jsonPrimitive.content }
        val tables38 = entities38.associateBy { it.jsonObject["tableName"]!!.jsonPrimitive.content }

        assertEquals(18, tables37.size)
        assertEquals(19, tables38.size)

        for ((tableName, table37El) in tables37) {
            val table38El = tables38[tableName]
            assertNotNull("Table $tableName from v37 must exist in v38", table38El)

            val t37Obj = table37El.jsonObject
            val t38Obj = table38El!!.jsonObject

            val fields37 = t37Obj["fields"]!!.jsonArray.toString()
            val fields38 = t38Obj["fields"]!!.jsonArray.toString()
            assertEquals("Table $tableName fields must be identical between v37 and v38", fields37, fields38)

            val pk37 = t37Obj["primaryKey"]!!.jsonObject.toString()
            val pk38 = t38Obj["primaryKey"]!!.jsonObject.toString()
            assertEquals("Table $tableName primary key must be identical between v37 and v38", pk37, pk38)
        }

        val newTable = tables38["disliked_song"]
        assertNotNull("disliked_song table must exist in v38", newTable)

        val newTableFields = newTable!!.jsonObject["fields"]!!.jsonArray
        val fieldNames = newTableFields.map {
            it.jsonObject["columnName"]!!.jsonPrimitive.content
        }.toSet()

        val expectedFields = setOf(
            "id", "title", "artist", "album", "duration",
            "thumbnailUrl", "normalizedTitle", "normalizedArtist", "dislikedAt"
        )
        assertEquals("disliked_song fields must match expected entity fields", expectedFields, fieldNames)
    }
}
