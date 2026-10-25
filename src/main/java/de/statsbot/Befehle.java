package de.statsbot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Befehle aus der App: Die App legt sie verschluesselt in einem geheimen ntfy.sh-Kanal ab,
 * der Bot holt sie bei jedem Check ab. Der Kanalname wird aus dem Daten-Schluessel abgeleitet,
 * steht also nirgends oeffentlich. Inhalt ist mit demselben Schluessel AES-GCM-verschluesselt.
 */
final class Befehle {

    record Befehl(String id, String art, int anzahl, String zeit) {}

    static String kanal(byte[] schluessel) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update("befehle:".getBytes(StandardCharsets.UTF_8));
        sha.update(Base64.getUrlEncoder().withoutPadding().encodeToString(schluessel).getBytes(StandardCharsets.UTF_8));
        return "mcs-" + HexFormat.of().formatHex(sha.digest()).substring(0, 24);
    }

    /** Alle Befehle seit der letzten Nachricht (oder den letzten 12 Stunden). */
    static List<Befehl> abholen(byte[] schluessel, String seit) throws Exception {
        String url = "https://ntfy.sh/" + kanal(schluessel) + "/json?poll=1&since=" + (seit == null || seit.isBlank() ? "12h" : seit);
        HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            .send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        List<Befehl> liste = new ArrayList<>();
        for (String zeile : r.body().split("\n")) {
            if (zeile.isBlank()) continue;
            JsonObject n = JsonParser.parseString(zeile).getAsJsonObject();
            if (!"message".equals(n.has("event") ? n.get("event").getAsString() : "")) continue;
            try {
                byte[] roh = Base64.getUrlDecoder().decode(n.get("message").getAsString().strip());
                JsonObject b = JsonParser.parseString(new String(Main.entschluessele(roh, schluessel), StandardCharsets.UTF_8)).getAsJsonObject();
                liste.add(new Befehl(n.get("id").getAsString(), b.get("befehl").getAsString(),
                    b.has("anzahl") ? b.get("anzahl").getAsInt() : 1, b.has("zeit") ? b.get("zeit").getAsString() : ""));
            } catch (Exception e) {
                // Nicht mit unserem Schluessel verschluesselt: ignorieren
            }
        }
        return liste;
    }

    private Befehle() {}
}
