package com.afforess.assembly.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.commons.dbutils.DbUtils;
import org.joda.time.Duration;

import play.Logger;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.mchange.v2.c3p0.ComboPooledDataSource;

public class DatabaseAccess {
	private final ComboPooledDataSource pool;
	private final LoadingCache<String, Integer> regionIdCache;
	private final LoadingCache<String, Integer> nationIdCache;
	private final LoadingCache<Integer, String> reverseIdCache;

	public DatabaseAccess(final ComboPooledDataSource pool, int cacheSize) {
		this.pool = pool;
		Logger.info("Creating Database Cache. Max Size: " + cacheSize);
		this.regionIdCache = CacheBuilder.newBuilder()
			.maximumSize(cacheSize)
			.expireAfterAccess(10, TimeUnit.MINUTES)
			.expireAfterWrite(1, TimeUnit.HOURS)
			.build(new CacheLoader<String, Integer>() {
			public Integer load(String key) throws SQLException {
				Connection conn = null;
				try {
					conn = pool.getConnection();
					PreparedStatement statement = conn.prepareStatement("SELECT id FROM assembly.region WHERE name = ?");
					statement.setString(1, key);
					ResultSet result = statement.executeQuery();
					if (result.next()) {
						return result.getInt(1);
					}
				} catch (SQLException e) {
					Logger.error("Unable to look up region", e);
				} finally {
					DbUtils.closeQuietly(conn);
				}
				return -1;
			}
		});

		this.nationIdCache = CacheBuilder.newBuilder()
			.maximumSize(cacheSize * 5)
			.expireAfterAccess(10, TimeUnit.MINUTES)
			.expireAfterWrite(1, TimeUnit.HOURS)
			.build(new CacheLoader<String, Integer>() {
			public Integer load(String key) throws SQLException {
				Connection conn = null;
				try {
					conn = pool.getConnection();
					PreparedStatement statement = conn.prepareStatement("SELECT id from assembly.nation WHERE name = ?");
					statement.setString(1, key);
					ResultSet result = statement.executeQuery();
					if (result.next()) {
						return result.getInt(1);
					}
				} catch (SQLException e) {
					Logger.error("Unable to look up nation id", e);
				} finally {
					DbUtils.closeQuietly(conn);
				}
				return -1;
			}
		});

		this.reverseIdCache = CacheBuilder.newBuilder()
			.maximumSize(cacheSize)
			.expireAfterAccess(10, TimeUnit.MINUTES)
			.expireAfterWrite(1, TimeUnit.HOURS)
			.build(new CacheLoader<Integer, String>() {
			public String load(Integer key) throws SQLException {
				Connection conn = null;
				try {
					conn = pool.getConnection();
					PreparedStatement statement = conn.prepareStatement("SELECT name from assembly.nation WHERE id = ?");
					statement.setInt(1, key);
					ResultSet result = statement.executeQuery();
					if (result.next()) {
						return result.getString(1);
					}
				} catch (SQLException e) {
					Logger.error("Unable to look up nation id", e);
				} finally {
					DbUtils.closeQuietly(conn);
				}
				throw new RuntimeException("No nation with id [" + key + "] found!");
			}
		});
	}

	public LoadingCache<String, Integer> getNationIdCache() {
		return nationIdCache;
	}

	public LoadingCache<Integer, String> getReverseIdCache() {
		return reverseIdCache;
	}

	public ComboPooledDataSource getPool() {
		return pool;
	}

	public boolean isValidAuthToken(int id, String authToken) {
		if (authToken == null) {
			return false;
		}
		Connection conn = null;
		try {
			conn = pool.getConnection();
			PreparedStatement statement = conn.prepareStatement("SELECT auth from assembly.nation_auth WHERE nation_id = ? AND time > ?");
			statement.setInt(1, id);
			statement.setLong(2, System.currentTimeMillis());
			ResultSet result = statement.executeQuery();
			while (result.next()) {
				if (authToken.equals(result.getString(1))) {
					return true;
				}
			}
		} catch (SQLException e) {
			Logger.error("Unable to verify auth token", e);
		} finally {
			DbUtils.closeQuietly(conn);
		}
		return false;
	}

	public String generateAuthToken(int id) {
		Connection conn = null;
		try {
			conn = pool.getConnection();
			PreparedStatement statement = conn.prepareStatement("SELECT auth from assembly.nation_auth WHERE nation_id = ? AND time > ?");
			statement.setInt(1, id);
			statement.setLong(2, System.currentTimeMillis());
			ResultSet result = statement.executeQuery();
			while (result.next()) {
				return result.getString(1);
			}
			String auth = Sha.hash256(id + "-" + System.nanoTime());
			statement = conn.prepareStatement("INSERT INTO assembly.nation_auth (nation_id, auth, time) VALUES (?, ?, ?)");
			statement.setInt(1, id);
			statement.setString(2, auth);
			statement.setLong(3, System.currentTimeMillis() + Duration.standardDays(7).getMillis());
			statement.executeUpdate();
			return auth;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		} finally {
			DbUtils.closeQuietly(conn);
		}
	}

	public LoadingCache<String, Integer> getRegionIdCache() {
		return regionIdCache;
	}

	public void markNationDead(String nation, Connection conn) throws ExecutionException, SQLException {
		markNationDead(getNationIdCache().get(nation), conn);
	}

	public void markNationDead(int nationId, Connection conn) throws SQLException {
		final boolean givenConn = conn != null;
		if (!givenConn) {
			conn = pool.getConnection();
		}
		try {
			PreparedStatement markDead = conn.prepareStatement("UPDATE assembly.nation SET alive = 0, wa_member = 0, cte = ? WHERE id = ?");
			markDead.setLong(1, System.currentTimeMillis() / 1000L);
			markDead.setInt(2, nationId);
			markDead.executeUpdate();
			
			PreparedStatement hasEndorsement = conn.prepareStatement("DELETE FROM assembly.endorsements WHERE endorsed = ? OR endorser = ?");
			hasEndorsement.setInt(1, nationId);
			hasEndorsement.setInt(2, nationId);
			hasEndorsement.execute();
			hasEndorsement.close();
		} finally {
			if (!givenConn) {
				DbUtils.closeQuietly(conn);
			}
		}
	}
}
