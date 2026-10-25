package de.statsbot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.auth.SessionService;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.status.ServerStatusInfo;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.statistic.BreakBlockStatistic;
import org.geysermc.mcprotocollib.protocol.data.game.statistic.BreakItemStatistic;
import org.geysermc.mcprotocollib.protocol.data.game.statistic.CraftItemStatistic;
import org.geysermc.mcprotocollib.protocol.data.game.statistic.CustomStatistic;
import org.geysermc.mcprotocollib.protocol.data.game.statistic.DropItemStatistic;
import org.geysermc.mcprotocollib.protocol.data.game.statistic.KillEntityStatistic;
import org.geysermc.mcprotocollib.protocol.data.game.statistic.KilledByEntityStatistic;
import org.geysermc.mcprotocollib.protocol.data.game.statistic.PickupItemStatistic;
import org.geysermc.mcprotocollib.protocol.data.game.statistic.Statistic;
import org.geysermc.mcprotocollib.protocol.data.game.statistic.UseItemStatistic;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundAwardStatsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetExperiencePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerCombatKillPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stats-Bot: meldet sich ganz kurz (hoechstens 10 Sekunden) auf dem Server an, fragt /bal und /topgeld ab
 * und geht sofort wieder. Ergebnis landet verschluesselt in docs/daten.enc fuer die Handy-Seite.
 *
 * Sicherheit:
 *  - Server aus oder der Besitzer selbst online: gar nicht erst joinen.
 *  - Jeder Schaden, wenig Leben, Tod: sofort raus.
 *  - Monster oder fremder Spieler in der Naehe: sofort raus.
 *  - Harte Obergrenze 10 Sekunden.
 *  - Der Bot bewegt sich nie und schreibt nur die zwei Befehle.
 *
 * Aufruf: login | abfrage
 * Umgebung: KS_SERVER, KS_AUTH_SCHLUESSEL, KS_DATEN_SCHLUESSEL (je 32 Byte, Base64url)
 */
public class Main {

    /** Serveradresse kommt aus einem Geheimnis, damit sie nicht oeffentlich im Code steht. */
    static final String HOST = System.getenv().getOrDefault("KS_SERVER", "").strip();
    static final Path AUTH_DATEI = Path.of("auth.enc");
    static final Path DATEN_DATEI = Path.of("docs", "daten.enc");
    static final long MAX_DAUER_MS = 10_000;
    static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    static final String BETRAG = "([\\d,]+(?:\\.\\d+)?[KkMmBb]?)";
    static final Pattern KONTOSTAND = Pattern.compile("^(?:Balance|Kontostand|Guthaben|Geld)\\s*:\\s*\\$" + BETRAG);
    // z. B. "[Top 10] 1. Thomtorro1 – $270.2M" oder "#2 Name $3.4M"
    static final Pattern RANG = Pattern.compile("^\\s*(?:\\[[^\\]]*\\]\\s*)?#?(\\d{1,2})[.):]?\\s+([A-Za-z0-9_.]{2,17})[^$\\d]{0,6}\\$" + BETRAG);
    static final Pattern PROXY_PORT = Pattern.compile("Connect to \\S+?:(\\d+)");

    static final Set<String> GEFAEHRLICH = Set.of("ZOMBIE", "ZOMBIE_VILLAGER", "HUSK", "DROWNED", "SKELETON", "STRAY",
        "BOGGED", "WITHER_SKELETON", "CREEPER", "SPIDER", "CAVE_SPIDER", "WITCH", "PILLAGER", "VINDICATOR", "EVOKER",
        "RAVAGER", "VEX", "BLAZE", "GHAST", "PIGLIN_BRUTE", "HOGLIN", "ZOGLIN", "PHANTOM", "SLIME", "MAGMA_CUBE",
        "SILVERFISH", "ENDERMITE", "GUARDIAN", "ELDER_GUARDIAN", "SHULKER", "WARDEN", "BREEZE", "CREAKING", "WITHER",
        "ENDER_DRAGON", "TNT", "ILLUSIONER");

    public static void main(String[] args) throws Exception {
        String modus = args.length > 0 ? args[0] : "abfrage";
        switch (modus) {
            case "login" -> login();
            case "abfrage" -> abfrage();
            case "pruefen" -> {
                // Nur Status, kein Join
                ServerStatusInfo proxy = status(HOST, 25565);
                String motd = text(proxy.getDescription());
                System.out.println("Proxy: " + proxy.getVersionInfo().getVersionName() + " | " + motd.replace("\n", " "));
                Matcher pm = PROXY_PORT.matcher(motd);
                if (pm.find()) {
                    ServerStatusInfo i = status(HOST, Integer.parseInt(pm.group(1)));
                    System.out.println("Server: " + i.getVersionInfo().getVersionName() + " Protokoll " + i.getVersionInfo().getProtocolVersion()
                        + " | online " + i.getPlayerInfo().getOnlinePlayers() + " " + i.getPlayerInfo().getPlayers().stream().map(GameProfile::getName).toList());
                    System.out.println("Bot spricht Protokoll " + new MinecraftProtocol().getCodec().getProtocolVersion() + " (" + new MinecraftProtocol().getCodec().getMinecraftVersion() + ")");
                }
            }
            case "sende" -> {
                // Testweise einen Befehl so einstellen, wie es die App tut: sende <befehl> [anzahl]
                byte[] k = schluessel("KS_DATEN_SCHLUESSEL");
                JsonObject b = new JsonObject();
                b.addProperty("befehl", args[1]);
                b.addProperty("anzahl", args.length > 2 ? Integer.parseInt(args[2]) : 1);
                b.addProperty("zeit", ZonedDateTime.now(ZONE).format(ISO));
                String text = Base64.getUrlEncoder().withoutPadding().encodeToString(verschluessele(b.toString().getBytes(StandardCharsets.UTF_8), k));
                var r = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("https://ntfy.sh/" + Befehle.kanal(k))).POST(java.net.http.HttpRequest.BodyPublishers.ofString(text)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
                System.out.println("Gesendet: " + r.statusCode());
            }
            case "befehle" -> {
                // Zum Pruefen: welche Befehle liegen im Kanal und lassen sich entschluesseln?
                for (Befehle.Befehl b : Befehle.abholen(schluessel("KS_DATEN_SCHLUESSEL"), "12h")) System.out.println(b);
            }
            case "historie" -> {
                // Einmalig: Geld-Geschichte aus den PC-Logs (Export des PC-Mods) in die verschluesselten Daten legen
                byte[] k = schluessel("KS_DATEN_SCHLUESSEL");
                JsonObject daten = JsonParser.parseString(new String(entschluessele(Files.readAllBytes(DATEN_DATEI), k), StandardCharsets.UTF_8)).getAsJsonObject();
                daten.add("historie", JsonParser.parseString(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8)));
                Files.write(DATEN_DATEI, verschluessele(GSON.toJson(daten).getBytes(StandardCharsets.UTF_8), k));
                System.out.println("Historie eingefuegt");
            }
            case "schluessel" -> {
                System.out.println("KS_AUTH_SCHLUESSEL=" + neuerSchluessel());
                System.out.println("KS_DATEN_SCHLUESSEL=" + neuerSchluessel());
            }
            default -> System.out.println("Unbekannt: " + modus);
        }
        System.exit(0);
    }

    // ---------------------------------------------------------------- Anmeldung

    static void login() throws Exception {
        java.util.function.Consumer<MsaDeviceCode> zeigeCode = code -> {
            System.out.println("ANMELDUNG: Gehe auf " + code.getVerificationUri() + " und gib den Code ein: " + code.getUserCode());
            System.out.flush();
        };
        JavaAuthManager auth = JavaAuthManager.create(MinecraftAuth.createHttpClient())
            .login(DeviceCodeMsaAuthService::new, zeigeCode);
        MinecraftProfile profil = auth.getMinecraftProfile().getUpToDate();
        auth.getMinecraftToken().getUpToDate();
        speichereAuth(auth);
        System.out.println("Angemeldet als " + profil.getName());
    }

    static JavaAuthManager ladeAuth() throws Exception {
        String json = new String(entschluessele(Files.readAllBytes(AUTH_DATEI), schluessel("KS_AUTH_SCHLUESSEL")), StandardCharsets.UTF_8);
        JavaAuthManager auth = JavaAuthManager.fromJson(MinecraftAuth.createHttpClient(), JsonParser.parseString(json).getAsJsonObject());
        auth.getChangeListeners().add(() -> {
            try {
                speichereAuth(auth);
            } catch (Exception e) {
                System.out.println("Auth speichern fehlgeschlagen: " + e);
            }
        });
        return auth;
    }

    static void speichereAuth(JavaAuthManager auth) throws Exception {
        byte[] json = JavaAuthManager.toJson(auth).toString().getBytes(StandardCharsets.UTF_8);
        Files.write(AUTH_DATEI, verschluessele(json, schluessel("KS_AUTH_SCHLUESSEL")));
    }

    // ---------------------------------------------------------------- Abfrage

    static void abfrage() throws Exception {
        JsonObject lauf = new JsonObject();
        lauf.addProperty("zeit", ZonedDateTime.now(ZONE).format(ISO));
        try {
            if (HOST.isEmpty()) throw new IllegalStateException("KS_SERVER fehlt");
            ladeDaten();
            holeBefehle();
            JavaAuthManager auth = ladeAuth();
            MinecraftProfile profil = auth.getMinecraftProfile().getUpToDate();
            String token = auth.getMinecraftToken().getUpToDate().getToken();
            lauf.addProperty("spieler", profil.getName());

            // 1. Laeuft der Server? der Proxy verraet den echten Port
            ServerStatusInfo proxy = status(HOST, 25565);
            String motd = text(proxy.getDescription());
            Matcher pm = PROXY_PORT.matcher(motd);
            int port;
            if (pm.find()) port = Integer.parseInt(pm.group(1));
            else if (proxy.getVersionInfo().getVersionName().toLowerCase().contains("paper")) port = 25565;
            else {
                lauf.addProperty("status", "server_aus");
                lauf.addProperty("grund", "Der Server ist gerade aus.");
                speichere(lauf);
                return;
            }
            ServerStatusInfo info;
            try {
                info = status(HOST, port);
            } catch (Exception e) {
                // Aternos faehrt gerade hoch oder runter
                lauf.addProperty("status", "server_aus");
                lauf.addProperty("grund", "Der Server startet oder stoppt gerade.");
                speichere(lauf);
                return;
            }
            JsonArray online = new JsonArray();
            boolean selbstOnline = false;
            if (info.getPlayerInfo() != null && info.getPlayerInfo().getPlayers() != null) {
                for (GameProfile p : info.getPlayerInfo().getPlayers()) {
                    online.add(p.getName());
                    if (p.getName().equalsIgnoreCase(profil.getName())) selbstOnline = true;
                }
            }
            lauf.add("online", online);
            lauf.addProperty("onlineAnzahl", info.getPlayerInfo() == null ? 0 : info.getPlayerInfo().getOnlinePlayers());
            lauf.addProperty("version", info.getVersionInfo().getVersionName());

            // 2. Nicht joinen, wenn du selbst gerade spielst (sonst wird er rausgeworfen)
            if (selbstOnline) {
                lauf.addProperty("status", "selbst_online");
                lauf.addProperty("grund", "Du bist gerade selbst online, der Bot hat nicht gejoint.");
                speichere(lauf);
                return;
            }

            // 3. Kurz joinen. Bei Schaden: sofort raus, kurz warten, wieder rein (hoechstens zweimal, nicht bei wenig Leben)
            for (int versuch = 0; ; versuch++) {
                Sitzung s = new Sitzung(HOST, port, profil, token, AUFTRAEGE, lauf);
                Sitzung.Ende ende = s.laufe();
                if (s.grund() != null) lauf.addProperty(ende == Sitzung.Ende.SCHADEN ? "notaus" : "grund", s.grund());
                boolean nochWasZuTun = !lauf.has("kontostand") || AUFTRAEGE.stream().anyMatch(a -> a.status.equals("offen"));
                if (ende == Sitzung.Ende.SCHADEN && versuch < 2 && s.leben() > 6 && nochWasZuTun) {
                    lauf.addProperty("wiederkehr", versuch + 1);
                    System.out.println("Kurz raus wegen Schaden, gleich wieder rein");
                    Thread.sleep(25_000);
                    continue;
                }
                break;
            }
            lauf.addProperty("status", lauf.has("kontostand") ? "ok" : lauf.has("notaus") ? "notaus" : "keine_daten");
        } catch (Exception e) {
            lauf.addProperty("status", "fehler");
            lauf.addProperty("grund", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        speichere(lauf);
    }

    static ServerStatusInfo status(String host, int port) throws Exception {
        CompletableFuture<ServerStatusInfo> ergebnis = new CompletableFuture<>();
        ClientSession c = ClientNetworkSessionFactory.factory()
            .setRemoteSocketAddress(new InetSocketAddress(host, port))
            .setProtocol(new MinecraftProtocol())
            .create();
        c.setFlag(MinecraftConstants.SERVER_INFO_HANDLER_KEY, (s, info) -> ergebnis.complete(info));
        c.addListener(new SessionAdapter() {
            @Override
            public void disconnected(DisconnectedEvent event) {
                ergebnis.completeExceptionally(new IllegalStateException("Status-Abfrage getrennt: " + text(event.getReason())));
            }
        });
        c.connect();
        try {
            return ergebnis.get(15, TimeUnit.SECONDS);
        } finally {
            c.disconnect(Component.text("fertig"));
        }
    }

    /** Minecraft-Statistiken: allgemeine Werte einzeln, Bloecke/Items als Summen, Kills nach Wesen. */
    static JsonObject statistikJson(ClientboundAwardStatsPacket a) {
        JsonObject allgemein = new JsonObject(), summen = new JsonObject(), kills = new JsonObject(), getoetetVon = new JsonObject();
        for (var e : a.getStatistics().object2IntEntrySet()) {
            Statistic s = e.getKey();
            int v = e.getIntValue();
            if (s instanceof CustomStatistic cs) allgemein.addProperty(cs.name().toLowerCase(), v);
            else if (s instanceof KillEntityStatistic k) kills.addProperty(k.getEntity().name().toLowerCase(), v);
            else if (s instanceof KilledByEntityStatistic k) getoetetVon.addProperty(k.getEntity().name().toLowerCase(), v);
            else {
                String art = switch (s) {
                    case BreakBlockStatistic b -> "abgebaut";
                    case CraftItemStatistic b -> "hergestellt";
                    case UseItemStatistic b -> "benutzt";
                    case BreakItemStatistic b -> "kaputt";
                    case PickupItemStatistic b -> "aufgehoben";
                    case DropItemStatistic b -> "fallengelassen";
                    default -> "sonstiges";
                };
                summen.addProperty(art, (summen.has(art) ? summen.get(art).getAsLong() : 0) + v);
            }
        }
        JsonObject o = new JsonObject();
        o.add("allgemein", allgemein);
        o.add("summen", summen);
        o.add("kills", kills);
        o.add("getoetetVon", getoetetVon);
        return o;
    }

    // ---------------------------------------------------------------- Speichern (verschluesselt)

    static JsonObject DATEN;
    static final List<Sitzung.Auftrag> AUFTRAEGE = new ArrayList<>();

    static void ladeDaten() {
        if (DATEN != null) return;
        DATEN = new JsonObject();
        if (!Files.exists(DATEN_DATEI)) return;
        try {
            DATEN = JsonParser.parseString(new String(entschluessele(Files.readAllBytes(DATEN_DATEI), schluessel("KS_DATEN_SCHLUESSEL")), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            System.out.println("Alte Daten nicht lesbar, fange neu an: " + e);
        }
    }

    /** Offene Auftraege aus dem letzten Lauf plus neue Befehle aus der App. */
    static void holeBefehle() {
        if (DATEN.has("auftraege")) {
            for (var e : DATEN.getAsJsonArray("auftraege")) {
                JsonObject a = e.getAsJsonObject();
                if ("offen".equals(str(a, "status"))) AUFTRAEGE.add(new Sitzung.Auftrag(str(a, "id"), str(a, "art"), a.has("anzahl") ? a.get("anzahl").getAsInt() : 1));
            }
        }
        try {
            List<Befehle.Befehl> neu = Befehle.abholen(schluessel("KS_DATEN_SCHLUESSEL"), str(DATEN, "befehleSeit"));
            for (Befehle.Befehl b : neu) {
                DATEN.addProperty("befehleSeit", b.id());
                if (AUFTRAEGE.stream().noneMatch(a -> a.id.equals(b.id()))) AUFTRAEGE.add(new Sitzung.Auftrag(b.id(), b.art(), b.anzahl()));
            }
            if (!neu.isEmpty()) System.out.println("Neue Befehle: " + neu.size());
        } catch (Exception e) {
            System.out.println("Befehle nicht abrufbar: " + e.getClass().getSimpleName());
        }
    }

    static void speichere(JsonObject lauf) throws Exception {
        byte[] k = schluessel("KS_DATEN_SCHLUESSEL");
        ladeDaten();
        JsonObject daten = DATEN;
        // Auftraege: aeltere behalten (letzte 30), aktuelle Staende ueberschreiben
        JsonArray auftr = new JsonArray();
        if (daten.has("auftraege")) {
            for (var e : daten.getAsJsonArray("auftraege")) {
                String id = str(e.getAsJsonObject(), "id");
                if (AUFTRAEGE.stream().noneMatch(a -> a.id.equals(id))) auftr.add(e);
            }
        }
        for (Sitzung.Auftrag a : AUFTRAEGE) {
            JsonObject j = new JsonObject();
            j.addProperty("id", a.id);
            j.addProperty("art", a.art);
            j.addProperty("anzahl", a.anzahl);
            j.addProperty("status", a.status);
            j.addProperty("ergebnis", a.ergebnis);
            j.add("zeit", lauf.get("zeit"));
            auftr.add(j);
        }
        while (auftr.size() > 30) auftr.remove(0);
        daten.add("auftraege", auftr);
        daten.add("letzterLauf", lauf);
        if (lauf.has("inventar")) {
            daten.add("inventar", lauf.get("inventar"));
            daten.add("inventarZeit", lauf.get("zeit"));
        }
        if (lauf.has("fenster")) daten.add("shopFenster", lauf.get("fenster"));
        if ("ok".equals(str(lauf, "status"))) daten.add("letzterErfolg", lauf);
        if (lauf.has("topgeld") && !lauf.getAsJsonArray("topgeld").isEmpty()) {
            daten.add("topgeld", lauf.get("topgeld"));
            daten.add("topgeldZeit", lauf.get("zeit"));
        }
        JsonArray verlauf = daten.has("verlauf") ? daten.getAsJsonArray("verlauf") : new JsonArray();
        if (lauf.has("kontostand")) {
            JsonObject punkt = new JsonObject();
            punkt.add("zeit", lauf.get("zeit"));
            punkt.add("kontostand", lauf.get("kontostand"));
            verlauf.add(punkt);
            while (verlauf.size() > 3000) verlauf.remove(0);
        }
        daten.add("verlauf", verlauf);
        if (lauf.has("statistiken")) {
            daten.add("statistiken", lauf.get("statistiken"));
            daten.add("statistikZeit", lauf.get("zeit"));
        }
        // Top 10 ueber die Zeit (fuer die Kurven der anderen)
        if (lauf.has("topgeld") && !lauf.getAsJsonArray("topgeld").isEmpty()) {
            JsonArray tv = daten.has("topVerlauf") ? daten.getAsJsonArray("topVerlauf") : new JsonArray();
            JsonObject p = new JsonObject();
            p.add("zeit", lauf.get("zeit"));
            JsonObject werte = new JsonObject();
            for (var r : lauf.getAsJsonArray("topgeld")) {
                JsonObject ro = r.getAsJsonObject();
                werte.add(ro.get("name").getAsString(), ro.get("geld"));
            }
            p.add("werte", werte);
            tv.add(p);
            while (tv.size() > 2000) tv.remove(0);
            daten.add("topVerlauf", tv);
        }
        // Wer war wann online, und lief der Server?
        JsonArray ov = daten.has("onlineVerlauf") ? daten.getAsJsonArray("onlineVerlauf") : new JsonArray();
        JsonObject op = new JsonObject();
        op.add("zeit", lauf.get("zeit"));
        op.addProperty("serverAn", !"server_aus".equals(str(lauf, "status")) && !"fehler".equals(str(lauf, "status")));
        op.add("spieler", lauf.has("online") ? lauf.get("online") : new JsonArray());
        ov.add(op);
        while (ov.size() > 3000) ov.remove(0);
        daten.add("onlineVerlauf", ov);
        JsonArray laeufe = daten.has("laeufe") ? daten.getAsJsonArray("laeufe") : new JsonArray();
        JsonObject kurz = new JsonObject();
        kurz.add("zeit", lauf.get("zeit"));
        kurz.addProperty("status", str(lauf, "status"));
        if (lauf.has("notaus")) kurz.add("notaus", lauf.get("notaus"));
        if (lauf.has("grund")) kurz.add("grund", lauf.get("grund"));
        laeufe.add(kurz);
        while (laeufe.size() > 200) laeufe.remove(0);
        daten.add("laeufe", laeufe);

        Files.createDirectories(DATEN_DATEI.getParent());
        Files.write(DATEN_DATEI, verschluessele(GSON.toJson(daten).getBytes(StandardCharsets.UTF_8), k));
        // Im Log nur Status, keine Werte (das Log ist oeffentlich)
        System.out.println("Lauf: " + str(lauf, "status") + (lauf.has("notaus") ? " / Notaus: " + str(lauf, "notaus") : "")
            + (lauf.has("grund") ? " / " + str(lauf, "grund") : ""));
    }

    // ---------------------------------------------------------------- Hilfen

    static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    static double betrag(String s) {
        s = s.replace(",", "").strip();
        double f = 1;
        char c = Character.toUpperCase(s.charAt(s.length() - 1));
        if (c == 'K' || c == 'M' || c == 'B') {
            f = c == 'K' ? 1e3 : c == 'M' ? 1e6 : 1e9;
            s = s.substring(0, s.length() - 1);
        }
        return Double.parseDouble(s) * f;
    }

    static String text(Component c) {
        if (c == null) return "";
        StringBuilder b = new StringBuilder();
        flach(c, b);
        return b.toString().replaceAll("§.", "");
    }

    private static void flach(Component c, StringBuilder b) {
        if (c instanceof TextComponent t) b.append(t.content());
        else if (c instanceof TranslatableComponent t) b.append(t.fallback() != null ? t.fallback() : t.key());
        for (Component kind : c.children()) flach(kind, b);
    }

    static byte[] schluessel(String name) {
        String wert = System.getenv(name);
        if (wert == null || wert.isBlank()) throw new IllegalStateException(name + " fehlt");
        byte[] k = Base64.getUrlDecoder().decode(wert.strip());
        if (k.length != 32) throw new IllegalStateException(name + " muss 32 Byte haben");
        return k;
    }

    static String neuerSchluessel() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(k);
    }

    /** AES-256-GCM, Format: 12 Byte IV + Geheimtext (inkl. Tag). Genau so entschluesselt die Handy-Seite. */
    static byte[] verschluessele(byte[] klar, byte[] k) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, iv));
        byte[] geheim = c.doFinal(klar);
        byte[] raus = new byte[12 + geheim.length];
        System.arraycopy(iv, 0, raus, 0, 12);
        System.arraycopy(geheim, 0, raus, 12, geheim.length);
        return raus;
    }

    static byte[] entschluessele(byte[] daten, byte[] k) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, daten, 0, 12));
        return c.doFinal(daten, 12, daten.length - 12);
    }
}
