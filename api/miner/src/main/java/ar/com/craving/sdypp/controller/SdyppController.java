package ar.com.craving.sdypp.controller;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.util.Objects;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpEntity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.MessageProperties;
import com.rabbitmq.client.ConnectionFactory;

import com.rethinkdb.RethinkDB;
import com.rethinkdb.net.Result;

@RestController
@RequestMapping("miner")
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

			try {
				Map<String, Object> args = new HashMap<String, Object>();
				args.put("x-max-length", 1);

				byte[] token = (new String("builder")).getBytes("UTF-8");
				mqChannel.queueDeclare("sdypp-tranfers", true, false, false, args);
				mqChannel.queueDeclare("sdypp-builder", true, false, false, args);

				mqChannel.basicPublish("", "sdypp-builder", null, token);
			} catch(Exception e) {
				System.out.println(e);
			}

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

	@RequestMapping(value = "/commit", method = RequestMethod.GET)
	public String getCommit() {
		Gson gson = new Gson();
		List<Object> rows = new ArrayList<Object>();
				
		GetResponse tokenBuilder;
		GetResponse lastCommit;
		String lastCommitJson = "{\"error\":true}";
		
		try {
			tokenBuilder = mqChannel.basicGet("sdypp-builder", false);	

			if ( Objects.isNull(tokenBuilder) ) {
				return "{\"error\":true,\"message\":\"No hay builder disponible.\"}";
			}
			
			lastCommit = mqChannel.basicGet("sdypp-tranfers", false);

			if ( !Objects.isNull(lastCommit) ) {
				mqChannel.basicNack(tokenBuilder.getEnvelope().getDeliveryTag(), false, true);
				mqChannel.basicNack(lastCommit.getEnvelope().getDeliveryTag(), false, true);
				return new String(lastCommit.getBody());
			}	
		} catch(Exception e) {
			System.out.println("ERROR DE COLAS DE RABBIT");
			System.out.println(e);
			return lastCommitJson;
		}

		Result<Object> cursor = r.db("sdypp")
								.table("tranfers")
								.distinct()
								.optArg("index", "origen")
								.map((origen) -> r.db("sdypp")
									.table("tranfers")
									.getAll(origen)
									.optArg("index", "origen")
									.nth(0))
								.group("destino")
								.ungroup()
								.map(destino -> destino.getField("reduction")
									.nth(0))
								.orderBy(r.asc("id"))
								.map(tr -> r.array(tr.getField("id"), 
								                   tr.getField("origen"), 
									               tr.getField("destino"), 
									               tr.getField("importe").coerceTo("string")))
								.run(rConnection);

		for (Object row : cursor) {
			rows.add(row);
		}
								
		// Get zero is because the query return an array not a result stream
		// 	this is a limitation of RethinkDB Java Driver.
		List<String> tranfersIds = new ArrayList<String>();
		String tranfers = "";	
		String tranfersHash = "";

		JsonArray tranfersObjs = gson.fromJson(gson.toJson(rows.get(0)), JsonArray.class);
		for ( JsonElement tr : tranfersObjs ) {
			JsonArray trArr = tr.getAsJsonArray();
			tranfersIds.add(trArr.get(0).getAsString());

			if ( tranfers != "" ) {
			  tranfers += ",";
			}

			tranfers += "[\"" + trArr.get(1).getAsString() + "\",\"" + trArr.get(2).getAsString() + "\",\"" + trArr.get(3).getAsString() + "\"]";			
		}

		tranfers = "[" + tranfers + "]";		
		
		cursor = r.db("sdypp")
				.table("blockchain")	
				.count()
				.run(rConnection);
		
		Integer id = 0;
		for (Object row : cursor) {
			id = gson.fromJson(gson.toJson(row), Integer.class);
		}			
		
		try {					
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(tranfers.getBytes("UTF-8"));
			StringBuffer hexString = new StringBuffer();
			for (int i = 0; i < hash.length; i++) {
				String hex = Integer.toHexString(0xff & hash[i]);
				if (hex.length() == 1) hexString.append('0');
				hexString.append(hex);
			}				
			tranfersHash = hexString.toString();

			JsonArray tranfersArray = gson.fromJson(tranfers, JsonArray.class);
			if (tranfersArray.size() == 0) {
				mqChannel.basicNack(tokenBuilder.getEnvelope().getDeliveryTag(), false, true);
				return "{\"error\":true,\"message\":\"No hay tranfers para minar.\"}";				
			}			
			
			double log10 = Math.log10(tranfersArray.size());
			int challenge = (int) log10;
			challenge += 1;
		
			lastCommitJson = "{\"index\":\""+id+"\",\"hash\": \""+tranfersHash+"\",\"challenge\":"+challenge+",\"tranfers\":"+tranfers+"}";			
			mqChannel.basicPublish("", "sdypp-tranfers", MessageProperties.PERSISTENT_TEXT_PLAIN, lastCommitJson.getBytes());		
		
			for (String tid : tranfersIds) {
				r.db("sdypp") 
				 .table("tranfers")
				 .get(tid)
				 .update(r.hashMap("commit", true))
				 .run(rConnection);				
			}

			mqChannel.basicNack(tokenBuilder.getEnvelope().getDeliveryTag(), false, true);
		} catch(Exception e) {
			System.out.println("ERROR COMMITEANDO");
			System.out.println(e.getMessage());
		}

		return lastCommitJson;
	}

	// -------------------------------------------------------------------------

	@RequestMapping(value = "/commit", method = RequestMethod.POST)
	@ResponseBody
	public String postCommit(HttpEntity<String> httpEntity) {
		Gson gson = new Gson();
		JsonObject commit = gson.fromJson(httpEntity.getBody(), JsonObject.class);
		
		GetResponse tokenBuilder;
		GetResponse lastCommit;
		String lastCommitJson = "{\"error\":true}";
		
		try {
			tokenBuilder = mqChannel.basicGet("sdypp-builder", false);	

			if ( Objects.isNull(tokenBuilder) ) {
				return "{\"error\":true,\"message\":\"No hay builder disponible.\"}";
			}
			
			lastCommit = mqChannel.basicGet("sdypp-tranfers", false);

			if ( Objects.isNull(lastCommit) )  {
				mqChannel.basicNack(tokenBuilder.getEnvelope().getDeliveryTag(), false, true);
				return "{\"error\":true,\"message\":\"No hay commit pendiente.\"}";
			}

			lastCommitJson = new String(lastCommit.getBody());	
		} catch(Exception e) {
			System.out.println("ERROR DE COLAS DE RABBIT");
			System.out.println(e.getMessage());
			return lastCommitJson;
		}

		try {
			JsonObject lastCommitObj = gson.fromJson(lastCommitJson, JsonObject.class);

			String commitHash = commit.get("hash").getAsString();
			String commitNonce = commit.get("nonce").getAsString();

			if ( !commitHash.equals(lastCommitObj.get("hash").getAsString()) ) {
				mqChannel.basicNack(lastCommit.getEnvelope().getDeliveryTag(), false, true);	
				mqChannel.basicNack(tokenBuilder.getEnvelope().getDeliveryTag(), false, true);
				return "{\"error\":true,\"message\":\"El commit expiro.\"}";
			}
		
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest((commitNonce + "." + commitHash).getBytes("UTF-8"));
			StringBuffer hexString = new StringBuffer();
			for (int i = 0; i < hash.length; i++) {
				String hex = Integer.toHexString(0xff & hash[i]);
				if (hex.length() == 1) hexString.append('0');
				hexString.append(hex);
			}		
			String targetHash = hexString.toString();
			Integer challenge = lastCommitObj.get("challenge").getAsInt();
			
			String pad = "0";
			pad = pad.repeat(challenge);

			if ( !targetHash.startsWith(pad) ) {
				mqChannel.basicNack(lastCommit.getEnvelope().getDeliveryTag(), false, true);	
				mqChannel.basicNack(tokenBuilder.getEnvelope().getDeliveryTag(), false, true);
				return "{\"error\":true,\"message\":\"El nonce es invalido.\"}";
			}

			// Insert new commit block
        	Timestamp timestamp = new Timestamp(System.currentTimeMillis());

			r.db("sdypp")
				.table("blockchain")
				.insert(
					r.hashMap("index", lastCommitObj.get("index").getAsInt())
					 .with("fecha", timestamp.getTime())
					 .with("nonce", commitNonce)
					 .with("result", targetHash)
					 .with("hash", lastCommitObj.get("hash").getAsString())
					 .with("challenge", lastCommitObj.get("challenge").getAsInt())
					 .with("tranfers", r.json(gson.toJson(lastCommitObj.get("tranfers"))))
				)
				.run(rConnection);

			// Actualizar saldos de las cuentas
			JsonArray tranfersArray = lastCommitObj.get("tranfers").getAsJsonArray();
			for (JsonElement tr : tranfersArray) {
				JsonArray trArr = tr.getAsJsonArray();
				System.out.println(trArr.get(0).getAsString() + " -> " + trArr.get(1).getAsString() + " $" + trArr.get(2).getAsInt());

				r.db("sdypp") 
				 .table("accounts")
				 .get(trArr.get(0).getAsString())
				 .update(acc -> acc.merge(r.hashMap("saldo", acc.getField("saldo").sub(trArr.get(2).getAsInt()))))
				 .run(rConnection);		

				r.db("sdypp") 
				 .table("accounts")
				 .get(trArr.get(1).getAsString())
				 .update(acc -> acc.merge(r.hashMap("saldo", acc.getField("saldo").add(trArr.get(2).getAsInt()))))
				 .run(rConnection);					
			}	

			// Borrar transferencias commiteadas.
			r.db("sdypp") 
				.table("tranfers")
				.filter(r.hashMap("commit", true))
				.delete()
				.run(rConnection);		

			mqChannel.basicAck(lastCommit.getEnvelope().getDeliveryTag(), false);	
			mqChannel.basicNack(tokenBuilder.getEnvelope().getDeliveryTag(), false, true);

			return "{\"error\":false,\"message\":\"Todo parece estar bien!.\"}";					
		} catch(Exception e) {
			System.out.println("ERROR DE COLAS DE RABBIT");
			System.out.println(e.getMessage());			
		}

		return lastCommitJson;
	}

	// -------------------------------------------------------------------------
}