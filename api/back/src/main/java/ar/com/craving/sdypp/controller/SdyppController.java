package ar.com.craving.sdypp.controller;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpEntity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;

import com.rethinkdb.RethinkDB;
import com.rethinkdb.net.Result;

@RestController
@RequestMapping("back")
public class SdyppController {

	// -------------------------------------------------------------------------

	private static final RethinkDB r = RethinkDB.r;
	private static com.rethinkdb.net.Connection rConnection;

	// -------------------------------------------------------------------------

	private static final ConnectionFactory mqFactory = new ConnectionFactory();
	private static com.rabbitmq.client.Connection mqConnection;
	private static Channel mqChannel;

	// -------------------------------------------------------------------------

	public SdyppController() {
		try {
			mqFactory.setHost("mq.balancer");
			mqFactory.setUsername("sdypp");
			mqFactory.setPassword("sdypp");
			mqConnection = mqFactory.newConnection();
			mqChannel = mqConnection.createChannel();

			rConnection = r.connection()
										 .hostname("storage.db.1")
										 .port(28015)
										 .connect();			
		} catch(Exception e) {
			System.out.println("ERROR DE CONSTRUCTOR");
			System.out.println(e);
		}
	}

	// -------------------------------------------------------------------------

	@RequestMapping(value = "/account", method = RequestMethod.GET)
	public String getAccounts() {
		Gson gson = new Gson();
		List<Object> accounts = new ArrayList<Object>();

		Result<Object> cursor = r.db("sdypp")
										         .table("accounts")
														 .pluck("id", "saldo")
										         .run(rConnection);

		for (Object account : cursor) {
			accounts.add(account);
		}

		return gson.toJson(accounts);
	}
	
	// -------------------------------------------------------------------------

	@RequestMapping(value = "/account", method = RequestMethod.POST)
	@ResponseBody
	public String postAccount(HttpEntity<String> httpEntity) {
		Gson gson = new Gson();
		List<JsonObject> rows = new ArrayList<JsonObject>();

		Object newAccount = gson.fromJson(httpEntity.getBody(), Object.class);

		Result<Object> cursor = r.db("sdypp")
														 .table("accounts")
														 .insert(newAccount)
														 .run(rConnection);

		for (Object row : cursor) {			
			JsonObject rowJson = gson.fromJson(gson.toJson(row), JsonObject.class);			
			rows.add(rowJson);
		}

		JsonArray keys = rows.get(0).get("generated_keys").getAsJsonArray();	
    String queueName = "account-" + keys.get(0).getAsString();
				
		try {
			// name , durable , exclusive , auto_delete , args
			mqChannel.queueDeclare(queueName, true, false, false, null);
			mqChannel.basicPublish("", queueName, null, queueName.getBytes("UTF-8"));	
		} catch(Exception e) {
			System.out.println(e);
		}

		return gson.toJson(rows);
	}

	// -------------------------------------------------------------------------

	@RequestMapping(value = "/login", method = RequestMethod.POST)
	@ResponseBody
	public String postLoginAccount(HttpEntity<String> httpEntity) {
		Gson gson = new Gson();
		List<Object> accounts = new ArrayList<Object>();

		JsonObject login = gson.fromJson(httpEntity.getBody(), JsonObject.class);

		Result<Object> cursor = r.db("sdypp")
														 .table("accounts")
														 .getAll(login.get("bcu").getAsString())
														 .filter(row -> row.g("privateKey")
																							 .eq(login.get("privateKey")
																							          .getAsString()))
														 .pluck("id", "token")
														 .run(rConnection);

		for (Object account : cursor) {
			accounts.add(account);
		}

		return gson.toJson(accounts);
	}

	// -------------------------------------------------------------------------

	@RequestMapping(value = "/blockchain", method = RequestMethod.GET)
	public String getBlockchain(@RequestParam("bcu") String bcu) {
		Gson gson = new Gson();
		List<Object> blockchain = new ArrayList<Object>();
		Result<Object> cursor;

		if (bcu == "") {
			cursor = r.db("sdypp")
					  .table("blockchain")
					  .orderBy(r.desc("index"))
					  .run(rConnection);
		} else {
			cursor = r.db("sdypp")
					  .table("blockchain")
					  .getAll(bcu).optArg("index", "bcu")
					  .orderBy(r.desc("index"))
					  .run(rConnection);
		}

		for (Object block : cursor) {
			blockchain.add(block);
		}

		return gson.toJson(blockchain.get(0));
	}

	// -------------------------------------------------------------------------

	@RequestMapping(value = "/tranfer", method = RequestMethod.POST)
	@ResponseBody
	public String postTranfer(HttpEntity<String> httpEntity) {
		Gson gson = new Gson();
		List<JsonObject> rows = new ArrayList<JsonObject>();

		JsonObject input = gson.fromJson(httpEntity.getBody(), JsonObject.class);	
		Object rawInput = gson.fromJson(httpEntity.getBody(), Object.class);		
		
		Result<Object> cursor = r.db("sdypp").table("accounts")
														 .getAll(input.get("token").getAsString())
														 .optArg("index", "token")
														 .pluck("id", "saldo")
														 .run(rConnection);
														 
		for (Object row : cursor) {			
			JsonObject rowJson = gson.fromJson(gson.toJson(row), JsonObject.class);			
			rows.add(rowJson);
		}
		
		// El remitente existe.
		if (rows.size() == 0) {
			return "{\"error\":true, \"message\":\"El remitente no es valido.\"}";
		}
		
		// El remitente es el del token.
		String origen = input.get("origen").getAsString();
		String idOfToken = rows.get(0).get("id").getAsString();
		
		if (!origen.equalsIgnoreCase(idOfToken)) {
			return "{\"error\":true,\"message\":\"No esta autorizado a transferir desde esta cuenta.\"}";
		}
		
		if (input.get("destino").getAsString() == input.get("origen").getAsString()) {
			return "{\"error\":true,\"message\":\"La cuenta de origen no puede ser la de destino.\"}";
		}
				
		rows.clear();
		
		cursor = r.db("sdypp")
							 .table("accounts")
							 .getAll(input.get("destino").getAsString())
							 .pluck("id", "saldo")
							 .run(rConnection);
																				 
		for (Object row : cursor) {			
			JsonObject rowJson = gson.fromJson(gson.toJson(row), JsonObject.class);			
			rows.add(rowJson);
		}
		
		// El destinatario existe.
		if (rows.size() == 0) {
			return "{\"error\":true,\"message\":\"El destinatario no existe.\"}";
		}
		
		rows.clear();

		cursor = r.db("sdypp")
							.table("tranfers")
							.insert(rawInput)
							.run(rConnection);

		for (Object row : cursor) {
			JsonObject rowJson = gson.fromJson(gson.toJson(row), JsonObject.class);			
			rows.add(rowJson);
		}

		return gson.toJson(rows);
	}

	// -------------------------------------------------------------------------

	@RequestMapping(value = "/tranfer", method = RequestMethod.GET)
	public String getTranfer(@RequestParam("bcu") String bcu) {
		Gson gson = new Gson();
		List<Object> tranfers = new ArrayList<Object>();

		Result<Object> cursor;

		if (bcu == "") {
			cursor = r.db("sdypp").table("tranfers")
								.run(rConnection);
		} else {
			cursor = r.db("sdypp").table("tranfers")
								.getAll(bcu).optArg("index", "bcu")
								.run(rConnection);
		}

		for (Object tranfer : cursor) {
			tranfers.add(tranfer);
		}

		return gson.toJson(tranfers);
	}

	// -------------------------------------------------------------------------

	@RequestMapping(value = "/status", method = RequestMethod.GET)
	public String getStatus(@RequestParam("token") String token) {
		Gson gson = new Gson();
		List<Object> accounts = new ArrayList<Object>();

		Result<Object> cursor = r.db("sdypp").table("accounts")
														 .getAll(token).optArg("index", "token")
														 .pluck("id", "saldo")
														 .run(rConnection);

		for (Object account : cursor) {
			accounts.add(account);
		}

		return gson.toJson(accounts);
	}

	// -------------------------------------------------------------------------

}