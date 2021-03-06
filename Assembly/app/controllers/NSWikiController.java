package controllers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import net.sourceforge.jwbf.core.RequestBuilder;
import net.sourceforge.jwbf.core.actions.util.HttpAction;
import net.sourceforge.jwbf.mediawiki.ApiRequestBuilder;
import net.sourceforge.jwbf.mediawiki.actions.util.MWAction;
import net.sourceforge.jwbf.mediawiki.bots.MediaWikiBot;

import org.apache.commons.dbutils.DbUtils;
import org.spout.cereal.config.ConfigurationNode;
import org.spout.cereal.config.yaml.YamlConfiguration;

import play.Logger;
import play.libs.Json;
import play.mvc.Result;
import play.mvc.Results;

import com.afforess.assembly.util.DatabaseAccess;
import com.afforess.assembly.util.Utils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.limewoodMedia.nsapi.NationStates;

public class NSWikiController  extends NationStatesController {
	private final String nswikiAdmin;
	private final String nswikiPass;
	public NSWikiController(DatabaseAccess access, YamlConfiguration config, NationStates api) {
		super(access, config, api);
		ConfigurationNode nswiki = getConfig().getChild("nswiki");
		nswikiAdmin = nswiki.getChild("admin").getString(null);
		nswikiPass = nswiki.getChild("password").getString(null);
	}

//	public Result calculateNationalStats(String nation) {
		
//	}

	public Result calculateRegionStats(String region) throws SQLException {
		Map<String, Object> regionStats = new HashMap<String, Object>();
		Connection conn = null;
		try {
			conn = getConnection();
			PreparedStatement select = conn.prepareStatement("SELECT id, name, flag, delegate, founder, title, alive FROM assembly.region WHERE name = ?");
			select.setString(1, Utils.sanitizeName(region));
			ResultSet result = select.executeQuery();
			if (result.next()) {
				final int regionId = result.getInt("id");
				regionStats.put("name", result.getString("name"));
				
				//Flag
				String flag = result.getString("flag").trim();
				if (flag.length() < 1) {
					flag = null;
				}
				if (result.getByte("alive") == 0) {
					flag = "http://nationstatesplusplus.com/nationstates/static/exregion.png";
				}
				if (flag != null) regionStats.put("flag", flag);

				//Founder
				String founder = result.getString("founder").trim();
				if (!founder.equals("0")) regionStats.put("founder", founder);
				
				//Delegate
				String delegate = result.getString("delegate").trim();
				if (!delegate.equals("0")) regionStats.put("delegate", delegate);
				
				//Title
				regionStats.put("title", result.getString("title"));
				
				//Alive
				regionStats.put("alive", result.getByte("alive") == 1);

				DbUtils.closeQuietly(result);
				DbUtils.closeQuietly(select);
				
				PreparedStatement stats = conn.prepareStatement("SELECT count(id) AS population, median(civilrightscore) as civilrights, median(economyscore) AS economy, median(politicalfreedomscore) as politicalfreedom, median(environment) as environment, median(socialequality) as socialequality, median(education) as education, median(lawandorder) as lawandorder, median(administration) as administration, median(welfare) as welfare, median(spirituality) as spirituality, median(defence) as defence, median(publictransport) as publictransport, median(healthcare) as healthcare, median(commerce) as commerce, median(publicsector) as publicsector, median(tax) as tax FROM assembly.nation WHERE alive = 1 AND region = ?");
				stats.setInt(1, regionId);
				result = stats.executeQuery();
				result.next();
				
				regionStats.put("population", result.getInt("population"));
				regionStats.put("civilrightscore", result.getInt("civilrights"));
				regionStats.put("politicalfreedomscore", result.getInt("politicalfreedom"));
				regionStats.put("environment", result.getInt("environment"));
				regionStats.put("socialequality", result.getInt("socialequality"));
				regionStats.put("education", result.getInt("education"));
				regionStats.put("lawandorder", result.getInt("lawandorder"));
				regionStats.put("administration", result.getInt("administration"));
				regionStats.put("welfare", result.getInt("welfare"));
				regionStats.put("spirituality", result.getInt("spirituality"));
				regionStats.put("defence", result.getInt("defence"));
				regionStats.put("publictransport", result.getInt("publictransport"));
				regionStats.put("healthcare", result.getInt("healthcare"));
				regionStats.put("commerce", result.getInt("commerce"));
				regionStats.put("publicsector", result.getInt("publicsector"));
				regionStats.put("tax", result.getInt("tax"));
				
				DbUtils.closeQuietly(result);
				DbUtils.closeQuietly(stats);
				
				stats = conn.prepareStatement("SELECT count(id) AS wa_members FROM assembly.nation WHERE alive = 1 AND wa_member = 1 AND region = ?");
				stats.setInt(1, regionId);
				result = stats.executeQuery();
				result.next();
				
				regionStats.put("wa_members", result.getInt("wa_members"));
			}
		} finally {
			DbUtils.closeQuietly(conn);
		}
		Result r = Utils.handleDefaultGetHeaders(request(), response(), String.valueOf(regionStats.hashCode()), "43200");
		if (r != null) {
			return r;
		}
		return ok(Json.toJson(regionStats)).as("application/json");
	}

	public Result verifyNationLogin() throws IOException, SQLException {
		Result ret = Utils.validateRequest(request(), response(), getAPI(), getDatabase(), false);
		if (ret != null) {
			return ret;
		}
		String nation = Utils.getPostValue(request(), "nation");
		String password = Utils.getPostValue(request(), "password");
		if (password == null || password.isEmpty() || password.length() > 40) {
			return Results.badRequest("Invalid password");
		}
		
		final String title;
		Connection conn = null;
		PreparedStatement select = null;
		ResultSet set = null;
		try {
			conn = getConnection();
			select = conn.prepareStatement("SELECT title FROM assembly.nation WHERE name = ?");
			select.setString(1, Utils.sanitizeName(nation));
			set = select.executeQuery();
			set.next();
			title = set.getString(1);
		} finally {
			DbUtils.closeQuietly(conn);
			DbUtils.closeQuietly(select);
			DbUtils.closeQuietly(set);
		}
		if (doesNSWikiUserExist(title)) {
			Logger.info("Updating password for " + title);
			changePassword(title, password);
			return Results.ok();
		}
		return createNSWikiUser(title, password);
	}

	private static void changePassword(String user, String password) throws IOException {
		Process cmdProc = Runtime.getRuntime().exec(new String[] {"php", "/etc/mediawiki/maintenance/changePassword.php", "--user=" + user + "", "--password=" + password + ""});
		BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(cmdProc.getInputStream()));
		String line;
		while ((line = stdoutReader.readLine()) != null) {
			Logger.info("[NSWIKI PASSWORD] " + line);
		}

		BufferedReader stderrReader = new BufferedReader(new InputStreamReader(cmdProc.getErrorStream()));
		while ((line = stderrReader.readLine()) != null) {
			Logger.warn("[NSWIKI PASSWORD] " + line);
		}
	}

	private static boolean doesNSWikiUserExist(String user) throws IOException {
		URL userPage = new URL("http://nswiki.org/index.php?title=User:" + URLEncoder.encode(user, "UTF-8"));
		HttpURLConnection conn = (HttpURLConnection) userPage.openConnection();
		conn.connect();
		return conn.getResponseCode() / 100 == 2;
	}

	private Result createNSWikiUser(String nation, String password) throws IOException {
		MediaWikiBot wikibot = new MediaWikiBot("http://nswiki.org/");
		wikibot.login(nswikiAdmin, nswikiPass);
		String result = wikibot.performAction(new CreateUser(nation, password));
		if (result.contains("success")) {
			return Results.ok();
		} else {
			Logger.warn("Unable to create NS Wiki user: " + result);
			return Results.internalServerError();
		}
	}

	private static class CreateUser extends MWAction {
		private String token = null;
		private int count = 0;
		private final String name;
		private final String password;
		public CreateUser(String name, String password) {
			this.name = name;
			this.password = password;
		}

		@Override
		public HttpAction getNextMessage() {
			try {
				RequestBuilder rb = new ApiRequestBuilder().action("createaccount").param("format", "json").param("name", URLEncoder.encode(name, "UTF-8")).param("password", URLEncoder.encode(password, "UTF-8"));
				if (token != null) {
					rb.param("token", token);
				}
				return rb.buildPost();
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException(e);
			}
		}

		@SuppressWarnings("unchecked")
		@Override
		public String processAllReturningText(final String s) {
			try {
				if (token == null) {
					Map<String, Object> result = new ObjectMapper().readValue(s, new TypeReference<HashMap<String,Object>>() {});
					token = ((Map<String, String>)result.get("createaccount")).get("token");
				}
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			return s;
		}

		@Override
		public boolean hasMoreMessages() {
			if (++count < 3) {
				return true;
			}
			return false;
		}
	}

}
