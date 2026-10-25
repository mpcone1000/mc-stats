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
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
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
            ServerStatusInfo info = status(HOST, port);
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

            // 3. Kurz joinen
            besuche(port, profil, token, lauf);
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

    static void besuche(int port, MinecraftProfile profil, String token, JsonObject lauf) throws Exception {
        List<String> zeilen = new ArrayList<>();
        Set<String> spieler = new LinkedHashSet<>();
        CountDownLatch fertig = new CountDownLatch(1);
        ScheduledExecutorService uhr = Executors.newSingleThreadScheduledExecutor();
        String[] abbruch = {null};
        double[][] position = {null};
        List<Object[]> wesen = new ArrayList<>(); // {Typ, x, y, z} - kann vor der eigenen Position ankommen
        boolean[] eilig = {false};
        float[] leben = {-1};
        long start = System.currentTimeMillis();

        ClientSession c = ClientNetworkSessionFactory.factory()
            .setRemoteSocketAddress(new InetSocketAddress(HOST, port))
            .setProtocol(new MinecraftProtocol(new GameProfile(profil.getId(), profil.getName()), token))
            .create();
        c.setFlag(MinecraftConstants.SESSION_SERVICE_KEY, new SessionService());

        Runnable raus = () -> {
            if (c.isConnected()) c.disconnect(Component.text("Bis gleich"));
            fertig.countDown();
        };
        java.util.function.Consumer<String> notaus = grund -> {
            if (abbruch[0] == null) abbruch[0] = grund;
            System.out.println("NOTAUS: " + grund);
            raus.run();
        };
        // Monster/Spieler in der Naehe: nur noch schnell /bal und nach 0,8 s raus (Schaden loest weiterhin sofort aus)
        Runnable pruefeGefahr = () -> {
            if (position[0] == null || eilig[0]) return;
            for (Object[] w : wesen) {
                double dx = (double) w[1] - position[0][0], dy = (double) w[2] - position[0][1], dz = (double) w[3] - position[0][2];
                double abstand = Math.sqrt(dx * dx + dy * dy + dz * dz);
                String typ = (String) w[0];
                if ((GEFAEHRLICH.contains(typ) && abstand < 12) || ("PLAYER".equals(typ) && abstand < 6)) {
                    eilig[0] = true;
                    abbruch[0] = typ + " in " + Math.round(abstand) + " Bloecken, nur kurz /bal";
                    System.out.println("VORSICHT: " + abbruch[0]);
                    c.send(new ServerboundChatCommandPacket("bal"));
                    uhr.schedule(raus, 800, TimeUnit.MILLISECONDS);
                    return;
                }
            }
        };

        c.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session s, Packet p) {
                try {
                    if (p instanceof ClientboundLoginPacket) {
                        // Befehle kurz nach dem Join, dann nach 4,5 s gehen
                        uhr.schedule(() -> { if (!eilig[0]) c.send(new ServerboundChatCommandPacket("bal")); }, 700, TimeUnit.MILLISECONDS);
                        uhr.schedule(() -> { if (!eilig[0]) c.send(new ServerboundChatCommandPacket("topgeld")); }, 1700, TimeUnit.MILLISECONDS);
                        uhr.schedule(raus, 4500, TimeUnit.MILLISECONDS);
                    } else if (p instanceof ClientboundPlayerPositionPacket pp) {
                        if (position[0] == null) {
                            position[0] = new double[]{pp.getPosition().getX(), pp.getPosition().getY(), pp.getPosition().getZ()};
                            pruefeGefahr.run();
                        }
                    } else if (p instanceof ClientboundSetHealthPacket h) {
                        if (leben[0] >= 0 && h.getHealth() < leben[0]) notaus.accept("Schaden bekommen (" + leben[0] + " -> " + h.getHealth() + ")");
                        else if (h.getHealth() <= 8) notaus.accept("Wenig Leben (" + h.getHealth() + ")");
                        leben[0] = h.getHealth();
                    } else if (p instanceof ClientboundPlayerCombatKillPacket) {
                        notaus.accept("Tod");
                    } else if (p instanceof ClientboundAddEntityPacket e) {
                        wesen.add(new Object[]{e.getType().name(), e.getX(), e.getY(), e.getZ()});
                        pruefeGefahr.run();
                    } else if (p instanceof ClientboundSystemChatPacket chat && !chat.isOverlay()) {
                        for (String z : text(chat.getContent()).split("\n")) if (!z.isBlank()) zeilen.add(z.strip());
                    } else if (p instanceof ClientboundPlayerInfoUpdatePacket info) {
                        for (PlayerListEntry e : info.getEntries()) {
                            if (e.getProfile() != null && e.getProfile().getName() != null) spieler.add(e.getProfile().getName());
                        }
                    }
                } catch (Exception ex) {
                    notaus.accept("Interner Fehler: " + ex);
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                String grund = text(event.getReason());
                if (!grund.equals("Bis gleich")) lauf.addProperty("getrennt", grund);
                fertig.countDown();
            }
        });

        uhr.schedule(() -> notaus.accept("10-Sekunden-Grenze"), MAX_DAUER_MS, TimeUnit.MILLISECONDS);
        c.connect();
        fertig.await(MAX_DAUER_MS + 3000, TimeUnit.MILLISECONDS);
        if (c.isConnected()) c.disconnect(Component.text("Bis gleich"));
        uhr.shutdownNow();

        lauf.addProperty("dauerMs", System.currentTimeMillis() - start);
        if (abbruch[0] != null) lauf.addProperty("notaus", abbruch[0]);
        if (leben[0] >= 0) lauf.addProperty("leben", leben[0]);

        JsonArray roh = new JsonArray();
        Map<Integer, JsonObject> top = new TreeMap<>();
        for (String z : zeilen) {
            roh.add(z);
            Matcher m = KONTOSTAND.matcher(z);
            if (m.find()) lauf.addProperty("kontostand", betrag(m.group(1)));
            m = RANG.matcher(z);
            if (m.find()) {
                JsonObject r = new JsonObject();
                r.addProperty("platz", Integer.parseInt(m.group(1)));
                r.addProperty("name", m.group(2));
                r.addProperty("geld", betrag(m.group(3)));
                top.put(Integer.parseInt(m.group(1)), r);
            }
        }
        lauf.add("nachrichten", roh);
        JsonArray topArr = new JsonArray();
        top.values().forEach(topArr::add);
        lauf.add("topgeld", topArr);
        JsonArray sp = new JsonArray();
        spieler.forEach(sp::add);
        if (!spieler.isEmpty()) lauf.add("online", sp);
        // Der Bot selbst zaehlt nicht als online
        if (lauf.has("online")) lauf.getAsJsonArray("online").remove(new com.google.gson.JsonPrimitive(profil.getName()));
        lauf.addProperty("status", lauf.has("kontostand") ? "ok" : abbruch[0] != null ? "notaus" : "keine_daten");
        if (!lauf.has("kontostand") && lauf.has("getrennt")) lauf.addProperty("grund", "Server hat getrennt: " + lauf.get("getrennt").getAsString());
    }

    // ---------------------------------------------------------------- Speichern (verschluesselt)

    static void speichere(JsonObject lauf) throws Exception {
        byte[] k = schluessel("KS_DATEN_SCHLUESSEL");
        JsonObject daten = new JsonObject();
        if (Files.exists(DATEN_DATEI)) {
            try {
                daten = JsonParser.parseString(new String(entschluessele(Files.readAllBytes(DATEN_DATEI), k), StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                System.out.println("Alte Daten nicht lesbar, fange neu an: " + e);
            }
        }
        daten.add("letzterLauf", lauf);
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
