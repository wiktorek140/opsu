/*
 * opsu! - an open-source osu! client
 * Copyright (C) 2014, 2015 Jeffrey Han
 *
 * opsu! is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * opsu! is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with opsu!.  If not, see <http://www.gnu.org/licenses/>.
 */

package itdelatrisu.opsu;

import itdelatrisu.opsu.GameData.Grade;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles game score data.
 */
public class Scores {
	/** Class encapsulating all score data. */
	public static class ScoreData implements Comparable<ScoreData> {
		/** The time when the score was achieved (Unix time). */
		public long timestamp;

		/** The beatmap and beatmap set IDs. */
		public int MID, MSID;

		/** Beatmap metadata. */
		public String title, artist, creator, version;

		/** Hit counts. */
		public int hit300, hit100, hit50, geki, katu, miss;

		/** The score. */
		public long score;

		/** The max combo. */
		public int combo;

		/** Whether or not a full combo was achieved. */
		public boolean perfect;

		/** Game mod bitmask. */
		public int mods;

		/**
		 * Empty constructor.
		 */
		public ScoreData() {}

		/**
		 * Constructor.
		 * @param rs the ResultSet to read from (at the current cursor position)
		 * @throws SQLException
		 */
		public ScoreData(ResultSet rs) throws SQLException {
			this.timestamp = rs.getLong(1);
			this.MID = rs.getInt(2);
			this.MSID = rs.getInt(3);
			this.title = rs.getString(4);
			this.artist = rs.getString(5);
			this.creator = rs.getString(6);
			this.version = rs.getString(7);
			this.hit300 = rs.getInt(8);
			this.hit100 = rs.getInt(9);
			this.hit50 = rs.getInt(10);
			this.geki = rs.getInt(11);
			this.katu = rs.getInt(12);
			this.miss = rs.getInt(13);
			this.score = rs.getLong(14);
			this.combo = rs.getInt(15);
			this.perfect = rs.getBoolean(16);
			this.mods = rs.getInt(17);
		}

		/**
		 * Returns the timestamp as a string.
		 */
		public String getTimeString() {
			return new SimpleDateFormat("M/d/yyyy h:mm:ss a").format(new Date(timestamp * 1000L));
		}

		/**
		 * Returns letter grade based on score data,
		 * or Grade.NULL if no objects have been processed.
		 * @see GameData#getGrade(int, int, int, int)
		 */
		public Grade getGrade() {
			return GameData.getGrade(hit300, hit100, hit50, miss);
		}

		@Override
		public String toString() {
			return String.format(
				"%s | ID: (%d, %d) | %s - %s [%s] (by %s) | " +
				"Hits: (%d, %d, %d, %d, %d, %d) | Score: %d (%d combo%s) | Mods: %d",
				getTimeString(), MID, MSID, artist, title, version, creator,
				hit300, hit100, hit50, geki, katu, miss, score, combo, perfect ? ", FC" : "", mods
			);
		}

		@Override
		public int compareTo(ScoreData that) {
			if (this.score != that.score)
				return Long.compare(this.score, that.score);
			else
				return Long.compare(this.timestamp, that.timestamp);
		}
	}

	/** Database connection. */
	private static Connection connection;

	/** Score insertion statement. */
	private static PreparedStatement insertStmt;

	/** Score select statement. */
	private static PreparedStatement selectMapStmt, selectMapSetStmt;

	// This class should not be instantiated.
	private Scores() {}

	/**
	 * Initializes the score database connection.
	 */
	public static void init() {
		// load the sqlite-JDBC driver using the current class loader
		try {
			Class.forName("org.sqlite.JDBC");
		} catch (ClassNotFoundException e) {
			ErrorHandler.error("Could not load sqlite-JDBC driver.", e, true);
		}

		// create a database connection
		try {
			connection = DriverManager.getConnection(String.format("jdbc:sqlite:%s", Options.SCORE_DB));
		} catch (SQLException e) {
			// if the error message is "out of memory", it probably means no database file is found
			ErrorHandler.error("Could not connect to score database.", e, true);
		}

		// create the database
		createDatabase();

		// prepare sql statements
		try {
			insertStmt = connection.prepareStatement(
				"INSERT INTO scores VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
			);
			selectMapStmt = connection.prepareStatement(
				"SELECT * FROM scores WHERE " +
				"MID = ? AND title = ? AND artist = ? AND creator = ? AND version = ?"
			);
			selectMapSetStmt = connection.prepareStatement(
				"SELECT * FROM scores WHERE " +
				"MSID = ? AND title = ? AND artist = ? AND creator = ? ORDER BY version DESC"
			);
		} catch (SQLException e) {
			ErrorHandler.error("Failed to prepare score insertion statement.", e, true);
		}
	}

	/**
	 * Creates the score database, if it does not exist.
	 */
	private static void createDatabase() {
		try (Statement stmt = connection.createStatement()) {
			String sql =
				"CREATE TABLE IF NOT EXISTS scores (" +
					"timestamp INTEGER PRIMARY KEY, " +
					"MID INTEGER, MSID INTEGER, " +
					"title TEXT, artist TEXT, creator TEXT, version TEXT, " +
					"hit300 INTEGER, hit100 INTEGER, hit50 INTEGER, " +
					"geki INTEGER, katu INTEGER, miss INTEGER, " +
					"score INTEGER, " +
					"combo INTEGER, " +
					"perfect BOOLEAN, " +
					"mods INTEGER" +
				")";
			stmt.executeUpdate(sql);
		} catch (SQLException e) {
			ErrorHandler.error("Could not create score database.", e, true);
		}
	}

	/**
	 * Adds the game score to the database.
	 * @param data the GameData object
	 */
	public static void addScore(ScoreData score) {
		try {
			insertStmt.setLong(1, score.timestamp);
			insertStmt.setInt(2, score.MID);
			insertStmt.setInt(3, score.MSID);
			insertStmt.setString(4, score.title);
			insertStmt.setString(5, score.artist);
			insertStmt.setString(6, score.creator);
			insertStmt.setString(7, score.version);
			insertStmt.setInt(8, score.hit300);
			insertStmt.setInt(9, score.hit100);
			insertStmt.setInt(10, score.hit50);
			insertStmt.setInt(11, score.geki);
			insertStmt.setInt(12, score.katu);
			insertStmt.setInt(13, score.miss);
			insertStmt.setLong(14, score.score);
			insertStmt.setInt(15, score.combo);
			insertStmt.setBoolean(16, score.perfect);
			insertStmt.setInt(17, score.mods);
			insertStmt.executeUpdate();
		} catch (SQLException e) {
			ErrorHandler.error("Failed to save score to database.", e, true);
		}
	}

	/**
	 * Retrieves the game scores for an OsuFile map.
	 * @param osu the OsuFile
	 * @return all scores for the beatmap
	 */
	public static ScoreData[] getMapScores(OsuFile osu) {
		List<ScoreData> list = new ArrayList<ScoreData>();
		try {
			selectMapStmt.setInt(1, osu.beatmapID);
			selectMapStmt.setString(2, osu.title);
			selectMapStmt.setString(3, osu.artist);
			selectMapStmt.setString(4, osu.creator);
			selectMapStmt.setString(5, osu.version);
			ResultSet rs = selectMapStmt.executeQuery();
			while (rs.next()) {
				ScoreData s = new ScoreData(rs);
				list.add(s);
			}
			rs.close();
		} catch (SQLException e) {
			ErrorHandler.error("Failed to read scores from database.", e, true);
			return null;
		}
		return getSortedArray(list);
	}

	/**
	 * Retrieves the game scores for an OsuFile map set.
	 * @param osu the OsuFile
	 * @return all scores for the beatmap set (Version, ScoreData[])
	 */
	public static Map<String, ScoreData[]> getMapSetScores(OsuFile osu) {
		Map<String, ScoreData[]> map = new HashMap<String, ScoreData[]>();
		try {
			selectMapSetStmt.setInt(1, osu.beatmapSetID);
			selectMapSetStmt.setString(2, osu.title);
			selectMapSetStmt.setString(3, osu.artist);
			selectMapSetStmt.setString(4, osu.creator);
			ResultSet rs = selectMapSetStmt.executeQuery();

			List<ScoreData> list = null;
			String version = "";  // sorted by version, so pass through and check for differences
			while (rs.next()) {
				ScoreData s = new ScoreData(rs);
				if (!s.version.equals(version)) {
					if (list != null)
						map.put(version, getSortedArray(list));
					version = s.version;
					list = new ArrayList<ScoreData>();
				}
				list.add(s);
			}
			if (list != null)
				map.put(version, getSortedArray(list));
			rs.close();
		} catch (SQLException e) {
			ErrorHandler.error("Failed to read scores from database.", e, true);
			return null;
		}
		return map;
	}

	/**
	 * Returns a sorted ScoreData array (in reverse order) from a List.
	 */
	private static ScoreData[] getSortedArray(List<ScoreData> list) {
		ScoreData[] scores = list.toArray(new ScoreData[list.size()]);
		Arrays.sort(scores, Collections.reverseOrder());
		return scores;
	}

	/**
	 * Closes the connection to the score database.
	 */
	public static void closeConnection() {
		if (connection != null) {
			try {
				insertStmt.close();
				selectMapStmt.close();
				selectMapSetStmt.close();
				connection.close();
			} catch (SQLException e) {
				ErrorHandler.error("Failed to close score database.", e, true);
			}
		}
	}

	/**
	 * Prints the entire database (for debugging purposes).
	 */
	protected static void printDatabase() {
		try (
			Statement stmt = connection.createStatement();
			ResultSet rs = stmt.executeQuery("SELECT * FROM scores ORDER BY timestamp ASC");
		) {
			while (rs.next())
				System.out.println(new ScoreData(rs));
		} catch (SQLException e) {
			ErrorHandler.error(null, e, false);
		}
	}
}
