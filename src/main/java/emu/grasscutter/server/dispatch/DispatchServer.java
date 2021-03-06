package emu.grasscutter.server.dispatch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.protobuf.ByteString;

import emu.grasscutter.Config;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.database.DatabaseHelper;
import emu.grasscutter.game.Account;
import emu.grasscutter.net.proto.QueryCurrRegionHttpRspOuterClass.QueryCurrRegionHttpRsp;
import emu.grasscutter.net.proto.QueryRegionListHttpRspOuterClass.QueryRegionListHttpRsp;
import emu.grasscutter.net.proto.RegionInfoOuterClass.RegionInfo;
import emu.grasscutter.net.proto.RegionSimpleInfoOuterClass.RegionSimpleInfo;
import emu.grasscutter.server.dispatch.json.*;
import emu.grasscutter.server.dispatch.json.ComboTokenReqJson.LoginTokenData;
import emu.grasscutter.server.event.dispatch.QueryAllRegionsEvent;
import emu.grasscutter.server.event.dispatch.QueryCurrentRegionEvent;
import emu.grasscutter.utils.FileUtils;
import express.Express;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.io.*;
import java.net.URLDecoder;
import java.util.*;

public final class DispatchServer {
	public static String query_region_list = "";
	public static String query_cur_region = "";

	private final Gson gson;
	private final String defaultServerName = "os_usa";

	public String regionListBase64;
	public HashMap<String, RegionData> regions;
	private Express httpServer;

	public DispatchServer() {
		this.regions = new HashMap<String, RegionData>();
		this.gson = new GsonBuilder().create();

		this.loadQueries();
		this.initRegion();
	}

	public Express getServer() {
		return httpServer;
	}

	public Gson getGsonFactory() {
		return gson;
	}

	public QueryCurrRegionHttpRsp getCurrRegion() {
		// Needs to be fixed by having the game servers connect to the dispatch server.
		if (Grasscutter.getConfig().RunMode.equalsIgnoreCase("HYBRID")) {
			return regions.get(defaultServerName).parsedRegionQuery;
		}

		Grasscutter.getLogger().warn("[Dispatch] Unsupported run mode for getCurrRegion()");
		return null;
	}

	public void loadQueries() {
		File file;

		file = new File(Grasscutter.getConfig().DATA_FOLDER + "query_region_list.txt");
		if (file.exists()) {
			query_region_list = new String(FileUtils.read(file));
		} else {
			Grasscutter.getLogger().warn("[Dispatch] query_region_list not found! Using default region list.");
		}

		file = new File(Grasscutter.getConfig().DATA_FOLDER + "query_cur_region.txt");
		if (file.exists()) {
			query_cur_region = new String(FileUtils.read(file));
		} else {
			Grasscutter.getLogger().warn("[Dispatch] query_cur_region not found! Using default current region.");
		}
	}

	private void initRegion() {
		try {
			byte[] decoded = Base64.getDecoder().decode(query_region_list);
			QueryRegionListHttpRsp rl = QueryRegionListHttpRsp.parseFrom(decoded);

			byte[] decoded2 = Base64.getDecoder().decode(query_cur_region);
			QueryCurrRegionHttpRsp regionQuery = QueryCurrRegionHttpRsp.parseFrom(decoded2);

			List<RegionSimpleInfo> servers = new ArrayList<>();
			List<String> usedNames = new ArrayList<>(); // List to check for potential naming conflicts
			if (Grasscutter.getConfig().RunMode.equalsIgnoreCase("HYBRID")) { // Automatically add the game server if in
																				// hybrid mode
				RegionSimpleInfo server = RegionSimpleInfo.newBuilder()
						.setName("os_usa")
						.setTitle(Grasscutter.getConfig().getGameServerOptions().Name)
						.setType("DEV_PUBLIC")
						.setDispatchUrl(
								"http" + (Grasscutter.getConfig().getDispatchOptions().FrontHTTPS ? "s" : "") + "://"
										+ (Grasscutter.getConfig().getDispatchOptions().PublicIp.isEmpty()
												? Grasscutter.getConfig().getDispatchOptions().Ip
												: Grasscutter.getConfig().getDispatchOptions().PublicIp)
										+ ":"
										+ (Grasscutter.getConfig().getDispatchOptions().PublicPort != 0
												? Grasscutter.getConfig().getDispatchOptions().PublicPort
												: Grasscutter.getConfig().getDispatchOptions().Port)
										+ "/query_cur_region/" + defaultServerName)
						.build();
				usedNames.add(defaultServerName);
				servers.add(server);

				RegionInfo serverRegion = regionQuery.getRegionInfo().toBuilder()
						.setGateserverIp((Grasscutter.getConfig().getGameServerOptions().PublicIp.isEmpty()
								? Grasscutter.getConfig().getGameServerOptions().Ip
								: Grasscutter.getConfig().getGameServerOptions().PublicIp))
						.setGateserverPort(Grasscutter.getConfig().getGameServerOptions().PublicPort != 0
								? Grasscutter.getConfig().getGameServerOptions().PublicPort
								: Grasscutter.getConfig().getGameServerOptions().Port)
						.setSecretKey(ByteString
								.copyFrom(FileUtils.read(Grasscutter.getConfig().KEY_FOLDER + "dispatchSeed.bin")))
						.build();

				QueryCurrRegionHttpRsp parsedRegionQuery = regionQuery.toBuilder().setRegionInfo(serverRegion).build();
				regions.put(defaultServerName, new RegionData(parsedRegionQuery,
						Base64.getEncoder().encodeToString(parsedRegionQuery.toByteString().toByteArray())));

			} else {
				if (Grasscutter.getConfig().getDispatchOptions().getGameServers().length == 0) {
					Grasscutter.getLogger()
							.error("[Dispatch] There are no game servers available. Exiting due to unplayable state.");
					System.exit(1);
				}
			}

			for (Config.DispatchServerOptions.RegionInfo regionInfo : Grasscutter.getConfig().getDispatchOptions()
					.getGameServers()) {
				if (usedNames.contains(regionInfo.Name)) {
					Grasscutter.getLogger().error("Region name already in use.");
					continue;
				}
				RegionSimpleInfo server = RegionSimpleInfo.newBuilder()
						.setName(regionInfo.Name)
						.setTitle(regionInfo.Title)
						.setType("DEV_PUBLIC")
						.setDispatchUrl(
								"http" + (Grasscutter.getConfig().getDispatchOptions().FrontHTTPS ? "s" : "") + "://"
										+ (Grasscutter.getConfig().getDispatchOptions().PublicIp.isEmpty()
												? Grasscutter.getConfig().getDispatchOptions().Ip
												: Grasscutter.getConfig().getDispatchOptions().PublicIp)
										+ ":" + (Grasscutter.getConfig().getDispatchOptions().PublicPort != 0
										? Grasscutter.getConfig().getDispatchOptions().PublicPort
										: Grasscutter.getConfig().getDispatchOptions().Port) + "/query_cur_region/" + regionInfo.Name)
						.build();
				usedNames.add(regionInfo.Name);
				servers.add(server);

				RegionInfo serverRegion = regionQuery.getRegionInfo().toBuilder()
						.setGateserverIp(regionInfo.Ip)
						.setGateserverPort(regionInfo.Port)
						.setSecretKey(ByteString
								.copyFrom(FileUtils.read(Grasscutter.getConfig().KEY_FOLDER + "dispatchSeed.bin")))
						.build();

				QueryCurrRegionHttpRsp parsedRegionQuery = regionQuery.toBuilder().setRegionInfo(serverRegion).build();
				regions.put(regionInfo.Name, new RegionData(parsedRegionQuery,
						Base64.getEncoder().encodeToString(parsedRegionQuery.toByteString().toByteArray())));
			}

			QueryRegionListHttpRsp regionList = QueryRegionListHttpRsp.newBuilder()
					.addAllRegionList(servers)
					.setClientSecretKey(rl.getClientSecretKey())
					.setClientCustomConfigEncrypted(rl.getClientCustomConfigEncrypted())
					.setEnableLoginPc(true)
					.build();

			this.regionListBase64 = Base64.getEncoder().encodeToString(regionList.toByteString().toByteArray());
		} catch (Exception e) {
			Grasscutter.getLogger().error("[Dispatch] Error while initializing region info!", e);
		}
	}

	public void start() throws Exception {
		httpServer = new Express(config -> {
			config.server(() -> {
				Server server = new Server();
				ServerConnector serverConnector;

				if(Grasscutter.getConfig().getDispatchOptions().UseSSL) {
					SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
					File keystoreFile = new File(Grasscutter.getConfig().getDispatchOptions().KeystorePath);

					if(keystoreFile.exists()) {
						try {
							sslContextFactory.setKeyStorePath(keystoreFile.getPath());
							sslContextFactory.setKeyStorePassword(Grasscutter.getConfig().getDispatchOptions().KeystorePassword);
						} catch (Exception e) {
							e.printStackTrace();
							Grasscutter.getLogger().warn("[Dispatch] Unable to load keystore. Trying default keystore password...");

							try {
								sslContextFactory.setKeyStorePath(keystoreFile.getPath());
								sslContextFactory.setKeyStorePassword("123456");
								Grasscutter.getLogger().warn("[Dispatch] The default keystore password was loaded successfully. Please consider setting the password to 123456 in config.json.");
							} catch (Exception e2) {
								Grasscutter.getLogger().warn("[Dispatch] Error while loading keystore!");
								e2.printStackTrace();
							}
						}

						serverConnector = new ServerConnector(server, sslContextFactory);
					} else {
						Grasscutter.getLogger().warn("[Dispatch] No SSL cert found! Falling back to HTTP server.");
						Grasscutter.getConfig().getDispatchOptions().UseSSL = false;

						serverConnector = new ServerConnector(server);
					}
				} else {
					serverConnector = new ServerConnector(server);
				}

				serverConnector.setPort(Grasscutter.getConfig().getDispatchOptions().Port);
				server.setConnectors(new Connector[]{serverConnector});
				return server;
			});

			config.enforceSsl = Grasscutter.getConfig().getDispatchOptions().UseSSL;
			if(Grasscutter.getConfig().DebugMode.equalsIgnoreCase("ALL")) {
				config.enableDevLogging();
			}
		});

		httpServer.get("/", (req, res) -> res.send("Welcome to Grasscutter"));

		httpServer.raw().error(404, ctx -> {
			if(Grasscutter.getConfig().DebugMode.equalsIgnoreCase("MISSING")) {
				Grasscutter.getLogger().info(String.format("[Dispatch] Potential unhandled %s request: %s", ctx.method(), ctx.url()));
			}
			ctx.contentType("text/html");
			ctx.result("<!doctype html><html lang=\"en\"><body><img src=\"https://http.cat/404\" /></body></html>"); // I'm like 70% sure this won't break anything.
		});

		// Dispatch
		httpServer.get("/query_region_list", (req, res) -> {
			// Log
			Grasscutter.getLogger().info(String.format("[Dispatch] Client %s request: query_region_list", req.ip()));

			// Invoke event.
			QueryAllRegionsEvent event = new QueryAllRegionsEvent(regionListBase64); event.call();
			// Respond with event result.
			res.send(event.getRegionList());
		});

		httpServer.get("/query_cur_region/:id", (req, res) -> {
			String regionName = req.params("id");
			// Log
			Grasscutter.getLogger().info(
					String.format("Client %s request: query_cur_region/%s", req.ip(), regionName));
			// Create a response form the request query parameters
			String response = "CAESGE5vdCBGb3VuZCB2ZXJzaW9uIGNvbmZpZw==";
			if (req.query().values().size() > 0) {
				response = regions.get(regionName).Base64;
			}

			// Invoke event.
			QueryCurrentRegionEvent event = new QueryCurrentRegionEvent(response); event.call();
			// Respond with event result.
			res.send(event.getRegionInfo());
		});

		// Login
		httpServer.post("/hk4e_global/mdk/shield/api/login", (req, res) -> {
			// Get post data
			LoginAccountRequestJson requestData = null;
			try {
				String body = req.ctx().body();
				Grasscutter.getLogger().info(body);
				requestData = getGsonFactory().fromJson(body, LoginAccountRequestJson.class);
			} catch (Exception ignored) {
			}

			// Create response json
			if (requestData == null) {
				return;
			}
			LoginResultJson responseData = new LoginResultJson();

			Grasscutter.getLogger()
					.info(String.format("[Dispatch] Client %s is trying to log in", req.ip()));

			// Login
			Account account = DatabaseHelper.getAccountByName(requestData.account);

			// Check if account exists, else create a new one.
			if (account == null) {
				// Account doesnt exist, so we can either auto create it if the config value is
				// set
				if (Grasscutter.getConfig().getDispatchOptions().AutomaticallyCreateAccounts) {
					// This account has been created AUTOMATICALLY. There will be no permissions
					// added.
					account = DatabaseHelper.createAccountWithId(requestData.account, 0);

					for (String permission : Grasscutter.getConfig().getDispatchOptions().defaultPermissions) {
						account.addPermission(permission);
					}

					if (account != null) {
						responseData.message = "OK";
						responseData.data.account.uid = account.getId();
						responseData.data.account.token = account.generateSessionKey();
						responseData.data.account.email = account.getEmail();

						Grasscutter.getLogger()
								.info(String.format("[Dispatch] Client %s failed to log in: Account %s created",
										req.ip(), responseData.data.account.uid));
					} else {
						responseData.retcode = -201;
						responseData.message = "Username not found, create failed.";

						Grasscutter.getLogger().info(String.format(
								"[Dispatch] Client %s failed to log in: Account create failed", req.ip()));
					}
				} else {
					responseData.retcode = -201;
					responseData.message = "Username not found.";

					Grasscutter.getLogger().info(String
							.format("[Dispatch] Client %s failed to log in: Account no found", req.ip()));
				}
			} else {
				// Account was found, log the player in
				responseData.message = "OK";
				responseData.data.account.uid = account.getId();
				responseData.data.account.token = account.generateSessionKey();
				responseData.data.account.email = account.getEmail();

				Grasscutter.getLogger().info(String.format("[Dispatch] Client %s logged in as %s", req.ip(),
						responseData.data.account.uid));
			}

			res.send(responseData);
		});

		// Login via token
		httpServer.post("/hk4e_global/mdk/shield/api/verify", (req, res) -> {
			// Get post data
			LoginTokenRequestJson requestData = null;
			try {
				String body = req.ctx().body();
				requestData = getGsonFactory().fromJson(body, LoginTokenRequestJson.class);
			} catch (Exception ignored) {
			}

			// Create response json
			if (requestData == null) {
				return;
			}
			LoginResultJson responseData = new LoginResultJson();
			Grasscutter.getLogger().info(String.format("[Dispatch] Client %s is trying to log in via token", req.ip()));

			// Login
			Account account = DatabaseHelper.getAccountById(requestData.uid);

			// Test
			if (account == null || !account.getSessionKey().equals(requestData.token)) {
				responseData.retcode = -111;
				responseData.message = "Game account cache information error";

				Grasscutter.getLogger()
						.info(String.format("[Dispatch] Client %s failed to log in via token", req.ip()));
			} else {
				responseData.message = "OK";
				responseData.data.account.uid = requestData.uid;
				responseData.data.account.token = requestData.token;
				responseData.data.account.email = account.getEmail();

				Grasscutter.getLogger().info(String.format("[Dispatch] Client %s logged in via token as %s",
						req.ip(), responseData.data.account.uid));
			}

			res.send(responseData);
		});

		// Exchange for combo token
		httpServer.post("/hk4e_global/combo/granter/login/v2/login", (req, res) -> {
			// Get post data
			ComboTokenReqJson requestData = null;
			try {
				String body = req.ctx().body();
				requestData = getGsonFactory().fromJson(body, ComboTokenReqJson.class);
			} catch (Exception ignored) {
			}

			// Create response json
			if (requestData == null || requestData.data == null) {
				return;
			}
			LoginTokenData loginData = getGsonFactory().fromJson(requestData.data, LoginTokenData.class); // Get login
			// data
			ComboTokenResJson responseData = new ComboTokenResJson();

			// Login
			Account account = DatabaseHelper.getAccountById(loginData.uid);

			// Test
			if (account == null || !account.getSessionKey().equals(loginData.token)) {
				responseData.retcode = -201;
				responseData.message = "Wrong session key.";

				Grasscutter.getLogger().info(
						String.format("[Dispatch] Client %s failed to exchange combo token", req.ip()));
			} else {
				responseData.message = "OK";
				responseData.data.open_id = loginData.uid;
				responseData.data.combo_id = "157795300";
				responseData.data.combo_token = account.generateLoginToken();

				Grasscutter.getLogger().info(
						String.format("[Dispatch] Client %s succeed to exchange combo token", req.ip()));
			}

			res.send(responseData);
		});

		// TODO: There are some missing route request types here (You can tell if they are missing if they are .all and not anything else)
		//  When http requests for theses routes are found please remove it from the list in DispatchHttpJsonHandler and update the route request types here

		// Agreement and Protocol
		// hk4e-sdk-os.hoyoverse.com
		httpServer.get("/hk4e_global/mdk/agreement/api/getAgreementInfos", new DispatchHttpJsonHandler("{\"retcode\":0,\"message\":\"OK\",\"data\":{\"marketing_agreements\":[]}}"));
		// hk4e-sdk-os.hoyoverse.com
		httpServer.post("/hk4e_global/combo/granter/api/compareProtocolVersion", new DispatchHttpJsonHandler("{\"retcode\":0,\"message\":\"OK\",\"data\":{\"modified\":true,\"protocol\":{\"id\":0,\"app_id\":4,\"language\":\"en\",\"user_proto\":\"\",\"priv_proto\":\"\",\"major\":7,\"minimum\":0,\"create_time\":\"0\",\"teenager_proto\":\"\",\"third_proto\":\"\"}}}"));

		// Game data
		// hk4e-api-os.hoyoverse.com
		httpServer.all("/common/hk4e_global/announcement/api/getAlertPic", new DispatchHttpJsonHandler("{\"retcode\":0,\"message\":\"OK\",\"data\":{\"total\":0,\"list\":[]}}"));
		// hk4e-api-os.hoyoverse.com
		httpServer.all("/common/hk4e_global/announcement/api/getAlertAnn", new DispatchHttpJsonHandler("{\"retcode\":0,\"message\":\"OK\",\"data\":{\"alert\":false,\"alert_id\":0,\"remind\":true}}"));
		// hk4e-api-os.hoyoverse.com
		httpServer.all("/common/hk4e_global/announcement/api/getAnnList", new DispatchHttpJsonHandler("{\"retcode\":0,\"message\":\"OK\",\"data\":{\"list\":[],\"total\":0,\"type_list\":[],\"alert\":false,\"alert_id\":0,\"timezone\":0,\"t\":\"" + System.currentTimeMillis() + "\"}}"));
		// hk4e-api-os-static.hoyoverse.com
		httpServer.all("/common/hk4e_global/announcement/api/getAnnContent", new DispatchHttpJsonHandler("{\"retcode\":0,\"message\":\"OK\",\"data\":{\"list\":[],\"total\":0}}"));
		// hk4e-sdk-os.hoyoverse.com
		httpServer.all("/hk4e_global/mdk/shopwindow/shopwindow/listPriceTier", new DispatchHttpJsonHandler("{\"retcode\":0,\"message\":\"OK\",\"data\":{\"suggest_currency\":\"USD\",\"tiers\":[]}}"));

		// Captcha
		// api-account-os.hoyoverse.com
		httpServer.post("/account/risky/api/check", new DispatchHttpJsonHandler("{\"retcode\":0,\"message\":\"OK\",\"data\":{\"id\":\"none\",\"action\":\"ACTION_NONE\",\"geetest\":null}}"));

		// Config
		// sdk-os-static.hoyoverse.com
		httpServer.get("/combo/box/api/config/sdk/combo", new DispatchHttpJsonHandler("{\"retcode\":0,\"message\":\"OK\",\"data\":{\"vals\":{\"disable_email_bind_skip\":\"false\",\"email_bind_remind_interval\":\"7\",\"email_bind_remind\":\"true\"}}}"));
		// hk4e-sdk-os-static.hoyoverse.com
		httpServer.get("/hk4e_global/combo/granter/api/getConfig", new DispatchHttpJsonHandler("{\"retcode\":0,\"message\":\"OK\",\"data\":{\"protocol\":true,\"qr_enabled\":false,\"log_level\":\"INFO\",\"announce_url\":\"https://webstatic-sea.hoyoverse.com/hk4e/announcement/index.html?sdk_presentation_style=fullscreen\\u0026sdk_screen_transparent=true\\u0026game_biz=hk4e_global\\u0026auth_appid=announcement\\u0026game=hk4e#/\",\"push_alias_type\":2,\"disable_ysdk_guard\":false,\"enable_announce_pic_popup\":true}}"));
		// hk4e-sdk-os-static.hoyoverse.com
		httpServer.get("/hk4e_global/mdk/shield/api/loadConfig", new DispatchHttpJsonHandler("{\"retcode\":0,\"message\":\"OK\",\"data\":{\"id\":6,\"game_key\":\"hk4e_global\",\"client\":\"PC\",\"identity\":\"I_IDENTITY\",\"guest\":false,\"ignore_versions\":\"\",\"scene\":\"S_NORMAL\",\"name\":\"????????????\",\"disable_regist\":false,\"enable_email_captcha\":false,\"thirdparty\":[\"fb\",\"tw\"],\"disable_mmt\":false,\"server_guest\":false,\"thirdparty_ignore\":{\"tw\":\"\",\"fb\":\"\"},\"enable_ps_bind_account\":false,\"thirdparty_login_configs\":{\"tw\":{\"token_type\":\"TK_GAME_TOKEN\",\"game_token_expires_in\":604800},\"fb\":{\"token_type\":\"TK_GAME_TOKEN\",\"game_token_expires_in\":604800}}}}"));
		// Test api?
		// abtest-api-data-sg.hoyoverse.com
		httpServer.post("/data_abtest_api/config/experiment/list", new DispatchHttpJsonHandler("{\"retcode\":0,\"success\":true,\"message\":\"\",\"data\":[{\"code\":1000,\"type\":2,\"config_id\":\"14\",\"period_id\":\"6036_99\",\"version\":\"1\",\"configs\":{\"cardType\":\"old\"}}]}"));

		// log-upload-os.mihoyo.com
		httpServer.all("/log/sdk/upload", new DispatchHttpJsonHandler("{\"code\":0}"));
		httpServer.all("/sdk/upload", new DispatchHttpJsonHandler("{\"code\":0}"));
		httpServer.post("/sdk/dataUpload", new DispatchHttpJsonHandler("{\"code\":0}"));
		// /perf/config/verify?device_id=xxx&platform=x&name=xxx
		httpServer.all("/perf/config/verify", new DispatchHttpJsonHandler("{\"code\":0}"));

		// Logging servers
		// overseauspider.yuanshen.com
		httpServer.all("/log", new DispatchHttpJsonHandler("{\"code\":0}"));
		// log-upload-os.mihoyo.com
		httpServer.all("/crash/dataUpload", new DispatchHttpJsonHandler("{\"code\":0}"));

		httpServer.get("/gacha", (req, res) -> res.send("<!doctype html><html lang=\"en\"><head><title>Gacha</title></head><body></body></html>"));

		httpServer.listen(Grasscutter.getConfig().getDispatchOptions().Port);
		Grasscutter.getLogger().info("[Dispatch] Dispatch server started on port " + httpServer.raw().port());
	}

	private Map<String, String> parseQueryString(String qs) {
		Map<String, String> result = new HashMap<>();
		if (qs == null) {
			return result;
		}

		int last = 0, next, l = qs.length();
		while (last < l) {
			next = qs.indexOf('&', last);
			if (next == -1) {
				next = l;
			}

			if (next > last) {
				int eqPos = qs.indexOf('=', last);
				try {
					if (eqPos < 0 || eqPos > next) {
						result.put(URLDecoder.decode(qs.substring(last, next), "utf-8"), "");
					} else {
						result.put(URLDecoder.decode(qs.substring(last, eqPos), "utf-8"),
								URLDecoder.decode(qs.substring(eqPos + 1, next), "utf-8"));
					}
				} catch (UnsupportedEncodingException e) {
					throw new RuntimeException(e); // will never happen, utf-8 support is mandatory for java
				}
			}
			last = next + 1;
		}
		return result;
	}

	public static class RegionData {
		QueryCurrRegionHttpRsp parsedRegionQuery;
		String Base64;

		public RegionData(QueryCurrRegionHttpRsp prq, String b64) {
			this.parsedRegionQuery = prq;
			this.Base64 = b64;
		}
	}
}
