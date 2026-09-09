package io.mosip.idrepository.anonymousprofile.client;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component
public class KeyManagerDecryptClient {

	private static final String JSON = "application/json";
	private static final byte[] VERSION_R2 = "VER_R2".getBytes(StandardCharsets.US_ASCII);
	private static final DateTimeFormatter TIMESTAMP_FORMAT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX")
					.withZone(ZoneOffset.UTC);
	private final ObjectMapper mapper;
	private final HttpClient httpClient;
	private final URI decryptUri;
	private final URI encryptUri;
	private final URI tokenUri;
	private final String clientId;
	private final String clientSecret;
	private final String authAppId;
	private final String cryptoAppId;
	private final String referenceId;

	public KeyManagerDecryptClient(ObjectMapper mapper,
			@Value("${mosip.kernel.keymanager.decrypt-url}") URI decryptUri,
			@Value("${mosip.kernel.keymanager.encrypt-url}") URI encryptUri,
			@Value("${mosip.authmanager.token-url}") URI tokenUri,
			@Value("${mosip.authmanager.client-id}") String clientId,
			@Value("${mosip.authmanager.client-secret}") String clientSecret,
			@Value("${mosip.authmanager.app-id}") String authAppId,
			@Value("${mosip.idrepo.app-id}") String cryptoAppId,
			@Value("${mosip.idrepo.crypto.refId.uin-data}") String referenceId) {
		this.mapper = mapper;
		this.httpClient = HttpClient.newBuilder()
				.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
				.build();
		this.decryptUri = decryptUri;
		this.encryptUri = encryptUri;
		this.tokenUri = tokenUri;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.authAppId = authAppId;
		this.cryptoAppId = cryptoAppId;
		this.referenceId = referenceId;
	}

	public JsonNode decrypt(byte[] encryptedData) throws IOException, InterruptedException {
		authenticate();
		byte[] plaintext = decryptBytes(encryptedData);
		byte[] nestedCiphertext = nestedCiphertext(plaintext);
		if (nestedCiphertext != null) {
			java.util.Arrays.fill(plaintext, (byte) 0);
			plaintext = decryptBytes(nestedCiphertext);
		}
		try {
			return mapper.readTree(plaintext);
		} finally {
			java.util.Arrays.fill(plaintext, (byte) 0);
		}
	}

	public byte[] encrypt(byte[] plaintext) throws IOException, InterruptedException {
		authenticate();
		ObjectNode request = mapper.createObjectNode();
		request.put("id", "mosip.idrepo.encrypt");
		request.put("version", "1.0");
		request.put("requesttime", timestamp());

		ObjectNode payload = request.putObject("request");
		payload.put("applicationId", cryptoAppId);
		payload.put("referenceId", referenceId);
		payload.put("timeStamp", timestamp());
		payload.put("data", Base64.getUrlEncoder().withoutPadding().encodeToString(plaintext));

		HttpRequest encryptRequest = HttpRequest.newBuilder(encryptUri)
				.header("Content-Type", JSON)
				.header("Accept", JSON)
				.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
				.build();

		HttpResponse<String> response = httpClient.send(encryptRequest,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		JsonNode body = parseSuccessfulResponse(response, "Key Manager encrypt");
		JsonNode data = body.path("response").path("data");
		if (!data.isTextual()) {
			throw new IOException("Key Manager encrypt response did not contain response.data");
		}
		return data.textValue().getBytes(StandardCharsets.US_ASCII);
	}

	private byte[] decryptBytes(byte[] encryptedData) throws IOException, InterruptedException {
		ObjectNode request = mapper.createObjectNode();
		request.put("id", "mosip.idrepo.decrypt");
		request.put("version", "1.0");
		request.put("requesttime", timestamp());

		ObjectNode payload = request.putObject("request");
		payload.put("applicationId", cryptoAppId);
		payload.put("referenceId", referenceId);
		payload.put("timeStamp", timestamp());
		payload.put("data", new String(encryptedData, StandardCharsets.US_ASCII));

		HttpRequest decryptRequest = HttpRequest.newBuilder(decryptUri)
				.header("Content-Type", JSON)
				.header("Accept", JSON)
				.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
				.build();

		HttpResponse<String> response = httpClient.send(decryptRequest,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		JsonNode body = parseSuccessfulResponse(response, "Key Manager decrypt");
		JsonNode data = body.path("response").path("data");
		if (!data.isTextual()) {
			throw new IOException("Key Manager decrypt response did not contain response.data");
		}

		return Base64.getUrlDecoder().decode(data.textValue());
	}

	private byte[] nestedCiphertext(byte[] plaintext) {
		int start = 0;
		while (start < plaintext.length && plaintext[start] == 0) {
			start++;
		}
		int end = plaintext.length;
		while (end > start && plaintext[end - 1] == 0) {
			end--;
		}
		if (start == end) {
			return null;
		}

		byte[] candidate = java.util.Arrays.copyOfRange(plaintext, start, end);
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(candidate);
			try {
				return startsWith(decoded, VERSION_R2) ? candidate : null;
			} finally {
				java.util.Arrays.fill(decoded, (byte) 0);
			}
		} catch (IllegalArgumentException ignored) {
			java.util.Arrays.fill(candidate, (byte) 0);
			return null;
		}
	}

	private boolean startsWith(byte[] value, byte[] prefix) {
		if (value.length < prefix.length) {
			return false;
		}
		for (int index = 0; index < prefix.length; index++) {
			if (value[index] != prefix[index]) {
				return false;
			}
		}
		return true;
	}

	private void authenticate() throws IOException, InterruptedException {
		ObjectNode request = mapper.createObjectNode();
		request.put("id", "mosip.idrepo.authenticate");
		request.put("version", "1.0");
		request.put("requesttime", timestamp());

		ObjectNode payload = request.putObject("request");
		payload.put("appId", authAppId);
		payload.put("clientId", clientId);
		payload.put("secretKey", clientSecret);

		HttpRequest tokenRequest = HttpRequest.newBuilder(tokenUri)
				.header("Content-Type", JSON)
				.header("Accept", JSON)
				.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
				.build();

		HttpResponse<String> response = httpClient.send(tokenRequest,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		parseSuccessfulResponse(response, "Auth Manager authentication");

	}

	private JsonNode parseSuccessfulResponse(HttpResponse<String> response, String operation) throws IOException {
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException(operation + " failed with HTTP status " + response.statusCode()
					+ ": " + response.body());
		}

		JsonNode body = mapper.readTree(response.body());
		if (body.path("errors").isArray() && !body.path("errors").isEmpty()) {
			throw new IOException(operation + " returned MOSIP errors: " + body.path("errors"));
		}
		return body;
	}

	private String timestamp() {
		return TIMESTAMP_FORMAT.format(Instant.now());
	}
}
