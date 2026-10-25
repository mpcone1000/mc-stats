package de.statsbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.kyori.adventure.text.Component;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import org.cloudburstmc.math.vector.Vector3i;
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
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.GlobalPos;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.ThunderStrengthValue;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundAwardStatsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundChangeDifficultyPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveMobEffectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateMobEffectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHeldSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetTimePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerCombatKillPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetExperiencePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Eine Verbindung zum Server: Daten holen und Auftraege aus der App erledigen.
 * Bei jedem Schaden, wenig Leben oder Tod wird sofort getrennt (Ende SCHADEN), Main entscheidet ueber Wiederkommen.
 */
final class Sitzung {

    enum Ende { FERTIG, SCHADEN, GETRENNT }

    /** Auftrag aus der App; erledigt wird er in dieser oder einer spaeteren Sitzung. */
    static final class Auftrag {
        final String id, art;
        final int anzahl;
        String status = "offen", ergebnis = "";

        Auftrag(String id, String art, int anzahl) {
            this.id = id;
            this.art = art;
            this.anzahl = anzahl;
        }
    }

    private static final Pattern GEKAUFT = Pattern.compile("^\\[Shop\\] Gekauft: (\\d+)x? (.+?) f(?:ü|ue)r");
    private static final Pattern POST = Pattern.compile("(?i)you have (\\d+) (?:new )?(?:messages?|mails?)");
    private static final Pattern KATEGORIE = Pattern.compile("(?i)kampf|combat|waffe|rüstung|ruestung|verschied|sonstig|misc|spezial|special|selten|rare|magie|totem|alles|items");

    private final String host;
    private final int port;
    private final MinecraftProfile profil;
    private final String token;
    private final List<Auftrag> auftraege;
    private final JsonObject lauf;

    // Zustand, vom Netzwerk-Thread gesetzt
    private final ItemStack[] inv = new ItemStack[46];
    private volatile int stateId, fensterId = -1, fensterState;
    private volatile ItemStack[] fenster;
    private volatile String fensterTitel = "";
    private volatile boolean inventarDa;
    private volatile double[] pos;
    private volatile float yaw, pitch, leben = -1;
    private volatile int essen = -1, level = -1;
    private volatile float xpFortschritt = -1, saettigung = -1;
    private volatile int entityId = Integer.MIN_VALUE, handSlot = -1;
    private volatile String welt, spielmodus, letzterTod, schwierigkeit;
    private volatile long tageszeit = -1;
    private volatile boolean regen, gewitter;
    private final Map<String, int[]> effekte = new ConcurrentHashMap<>();
    private final Map<UUID, String> namen = new ConcurrentHashMap<>();
    private volatile JsonObject statistiken;
    private volatile boolean aktiv = true;
    private volatile Ende ende = Ende.FERTIG;
    private volatile String grund;
    private int sequenz = 1;
    private final List<String> zeilen = new CopyOnWriteArrayList<>();
    private final Set<String> spieler = new LinkedHashSet<>();
    private final List<Object[]> wesen = new CopyOnWriteArrayList<>();
    private final JsonArray fensterLog = new JsonArray();
    private ClientSession c;

    Sitzung(String host, int port, MinecraftProfile profil, String token, List<Auftrag> auftraege, JsonObject lauf) {
        this.host = host;
        this.port = port;
        this.profil = profil;
        this.token = token;
        this.auftraege = auftraege;
        this.lauf = lauf;
    }

    String grund() {
        return grund;
    }

    float leben() {
        return leben;
    }

    Ende laufe() throws Exception {
        CountDownLatch getrennt = new CountDownLatch(1);
        c = ClientNetworkSessionFactory.factory()
            .setRemoteSocketAddress(new InetSocketAddress(host, port))
            .setProtocol(new MinecraftProtocol(new GameProfile(profil.getId(), profil.getName()), token))
            .create();
        c.setFlag(MinecraftConstants.SESSION_SERVICE_KEY, new SessionService());
        c.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session s, Packet p) {
                try {
                    empfange(p);
                } catch (Exception e) {
                    notaus(Ende.GETRENNT, "Interner Fehler: " + e);
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                String g = Main.text(event.getReason());
                if (aktiv && !g.equals("Bis gleich")) {
                    ende = Ende.GETRENNT;
                    grund = "Server hat getrennt: " + g;
                }
                aktiv = false;
                getrennt.countDown();
            }
        });
        boolean mitAuftraegen = auftraege.stream().anyMatch(a -> a.status.equals("offen"));
        long grenze = mitAuftraegen ? 45_000 : Main.MAX_DAUER_MS;
        Thread ablauf = new Thread(() -> {
            try {
                ablauf();
            } catch (InterruptedException ignored) {
                // abgebrochen
            } catch (Exception e) {
                grund = "Ablauf: " + e;
            }
            trenne();
        }, "Ablauf");
        ablauf.setDaemon(true);
        long start = System.currentTimeMillis();
        c.connect();
        ablauf.start();
        if (!getrennt.await(grenze, TimeUnit.MILLISECONDS)) {
            if (grund == null) grund = "Zeitgrenze " + grenze / 1000 + " s";
            trenne();
        }
        ablauf.interrupt();
        lauf.addProperty("dauerMs", (lauf.has("dauerMs") ? lauf.get("dauerMs").getAsLong() : 0) + System.currentTimeMillis() - start);
        werteAus();
        return ende;
    }

    // ------------------------------------------------------------------ Netzwerk

    private void empfange(Packet p) {
        if (p instanceof ClientboundPlayerPositionPacket pp) {
            c.send(new ServerboundAcceptTeleportationPacket(pp.getId(), 0, 0, 0, 0, 0));
            pos = new double[]{pp.getPosition().getX(), pp.getPosition().getY(), pp.getPosition().getZ()};
            yaw = pp.getYRot();
            pitch = pp.getXRot();
        } else if (p instanceof ClientboundLoginPacket l) {
            entityId = l.getEntityId();
            welt(l.getCommonPlayerSpawnInfo());
        } else if (p instanceof ClientboundRespawnPacket r) {
            welt(r.getCommonPlayerSpawnInfo());
        } else if (p instanceof ClientboundSetTimePacket t) {
            for (var uhr : t.getClockUpdates().values()) {
                tageszeit = uhr.totalTicks();
                break;
            }
        } else if (p instanceof ClientboundGameEventPacket g) {
            switch (g.getNotification()) {
                case START_RAINING -> regen = true;
                case STOP_RAINING -> {
                    regen = false;
                    gewitter = false;
                }
                case THUNDER_LEVEL_CHANGE -> gewitter = g.getValue() instanceof ThunderStrengthValue tv && tv.getStrength() > 0.5f;
                default -> { }
            }
        } else if (p instanceof ClientboundUpdateMobEffectPacket e) {
            if (e.getEntityId() == entityId) effekte.put(e.getEffect().name().toLowerCase(), new int[]{e.getAmplifier(), e.getDuration()});
        } else if (p instanceof ClientboundRemoveMobEffectPacket e) {
            if (e.getEntityId() == entityId) effekte.remove(e.getEffect().name().toLowerCase());
        } else if (p instanceof ClientboundSetHeldSlotPacket hs) {
            handSlot = hs.getSlot();
        } else if (p instanceof ClientboundChangeDifficultyPacket d) {
            schwierigkeit = d.getDifficulty().name().toLowerCase();
        } else if (p instanceof ClientboundSetHealthPacket h) {
            if (leben >= 0 && h.getHealth() < leben) notaus(Ende.SCHADEN, "Schaden bekommen (" + leben + " -> " + h.getHealth() + ")");
            else if (h.getHealth() <= 8) notaus(Ende.SCHADEN, "Wenig Leben (" + h.getHealth() + ")");
            leben = h.getHealth();
            essen = h.getFood();
            saettigung = h.getSaturation();
        } else if (p instanceof ClientboundPlayerCombatKillPacket) {
            notaus(Ende.SCHADEN, "Tod");
        } else if (p instanceof ClientboundSetExperiencePacket xp) {
            level = xp.getLevel();
            xpFortschritt = xp.getExperience();
        } else if (p instanceof ClientboundAwardStatsPacket a) {
            statistiken = Main.statistikJson(a);
        } else if (p instanceof ClientboundAddEntityPacket e) {
            wesen.add(new Object[]{e.getType().name(), e.getX(), e.getY(), e.getZ(), e.getUuid()});
        } else if (p instanceof ClientboundSystemChatPacket chat && !chat.isOverlay()) {
            for (String z : Main.text(chat.getContent()).split("\n")) if (!z.isBlank()) zeilen.add(z.strip());
        } else if (p instanceof ClientboundPlayerInfoUpdatePacket info) {
            for (PlayerListEntry e : info.getEntries()) {
                if (e.getProfile() != null && e.getProfile().getName() != null) {
                    synchronized (spieler) { spieler.add(e.getProfile().getName()); }
                    if (e.getProfileId() != null) namen.put(e.getProfileId(), e.getProfile().getName());
                }
            }
        } else if (p instanceof ClientboundContainerSetContentPacket ct) {
            if (ct.getContainerId() == 0) {
                ItemStack[] it = ct.getItems();
                synchronized (inv) { for (int i = 0; i < Math.min(46, it.length); i++) inv[i] = it[i]; }
                stateId = ct.getStateId();
                inventarDa = true;
            } else if (ct.getContainerId() == fensterId) {
                fenster = ct.getItems();
                fensterState = ct.getStateId();
                merkeFenster();
            }
        } else if (p instanceof ClientboundContainerSetSlotPacket sl) {
            if (sl.getContainerId() == 0 && sl.getSlot() >= 0 && sl.getSlot() < 46) {
                synchronized (inv) { inv[sl.getSlot()] = sl.getItem(); }
                stateId = sl.getStateId();
            } else if (sl.getContainerId() == fensterId && fenster != null && sl.getSlot() >= 0 && sl.getSlot() < fenster.length) {
                fenster[sl.getSlot()] = sl.getItem();
                fensterState = sl.getStateId();
            }
        } else if (p instanceof ClientboundOpenScreenPacket os) {
            fensterId = os.getContainerId();
            fensterTitel = Main.text(os.getTitle());
            fenster = null;
        } else if (p instanceof ClientboundContainerClosePacket) {
            fensterId = -1;
            fenster = null;
        }
    }

    private void welt(PlayerSpawnInfo s) {
        if (s == null) return;
        if (s.getWorldName() != null) welt = s.getWorldName().asString();
        if (s.getGameMode() != null) spielmodus = s.getGameMode().name().toLowerCase();
        GlobalPos t = s.getLastDeathPos();
        if (t != null) letzterTod = t.getX() + " " + t.getY() + " " + t.getZ() + " " + (t.getDimension() == null ? "" : t.getDimension().asString());
    }

    private void notaus(Ende e, String g) {
        if (!aktiv) return;
        ende = e;
        grund = g;
        System.out.println("NOTAUS: " + g);
        trenne();
    }

    private void trenne() {
        aktiv = false;
        if (c != null && c.isConnected()) c.disconnect(Component.text("Bis gleich"));
    }

    private void schlafe(long ms) throws InterruptedException {
        long bis = System.currentTimeMillis() + ms;
        while (aktiv && System.currentTimeMillis() < bis) Thread.sleep(25);
        if (!aktiv) throw new InterruptedException();
    }

    private void befehl(String b) {
        c.send(new ServerboundChatCommandPacket(b));
    }

    // ------------------------------------------------------------------ Ablauf

    private void ablauf() throws Exception {
        for (int i = 0; i < 60 && (pos == null || !inventarDa); i++) schlafe(50);
        schlafe(250);
        c.send(new ServerboundClientCommandPacket(ClientCommand.REQUEST_STATS));
        schlafe(300);
        befehl("bal");
        schlafe(800);
        befehl("topgeld");
        schlafe(1200);
        List<Auftrag> offen = auftraege.stream().filter(a -> a.status.equals("offen")).toList();
        if (offen.isEmpty()) {
            schlafe(1000);
            return;
        }
        for (Auftrag a : offen) {
            if (!aktiv) return;
            try {
                a.ergebnis = switch (a.art) {
                    case "essen" -> essen();
                    case "totem" -> totemAusruesten();
                    case "kaufen" -> totemsKaufen(Math.max(1, Math.min(16, a.anzahl)));
                    case "einbauen" -> einbauen();
                    case "erkunden" -> erkunden();
                    case "heim" -> teleport("home", "nach Hause");
                    case "spawn" -> teleport("spawn", "zum Spawn");
                    default -> "Unbekannter Befehl";
                };
                a.status = "erledigt";
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                a.status = "fehler";
                a.ergebnis = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            System.out.println("Auftrag " + a.art + ": " + a.status);
            schlafe(400);
        }
        schlafe(800);
    }

    // ------------------------------------------------------------------ Inventar

    private int finde(IntPredicate passt, int von, int bis) {
        synchronized (inv) {
            // Hotbar zuerst, dann Rest
            for (int i = 36; i <= 44; i++) if (i >= von && i <= bis && inv[i] != null && passt.test(inv[i].getId())) return i;
            for (int i = von; i <= bis; i++) if (inv[i] != null && passt.test(inv[i].getId())) return i;
        }
        return -1;
    }

    private int finde(List<String> namen) {
        for (String n : namen) {
            int id = Items.id(n), s = finde(x -> x == id, 9, 44);
            if (s >= 0) return s;
        }
        return -1;
    }

    private int anzahl(int id) {
        int n = 0;
        synchronized (inv) { for (ItemStack s : inv) if (s != null && s.getId() == id) n += s.getAmount(); }
        return n;
    }

    private void klick(int fenster, int state, int slot, ContainerActionType typ, ContainerAction aktion) {
        c.send(new ServerboundContainerClickPacket(fenster, state, slot, typ, aktion, null, new Int2ObjectOpenHashMap<>()));
    }

    /** Legt das Item aus dem Inventar-Slot in die Hand (Hotbar-Platz 9, falls es nicht schon in der Hotbar liegt). */
    private void inDieHand(int slot) throws InterruptedException {
        int hot;
        if (slot >= 36 && slot <= 44) hot = slot - 36;
        else {
            klick(0, stateId, slot, ContainerActionType.MOVE_TO_HOTBAR_SLOT, MoveToHotbarAction.SLOT_9);
            hot = 8;
            schlafe(350);
        }
        c.send(new ServerboundSetCarriedItemPacket(hot));
        schlafe(150);
    }

    // ------------------------------------------------------------------ Auftraege

    private String essen() throws InterruptedException {
        if (essen >= 20) return "War schon satt";
        int gegessen = 0;
        while (aktiv && essen < 19 && gegessen < 4) {
            int slot = finde(Items.ESSEN);
            if (slot < 0) return gegessen > 0 ? gegessen + "x gegessen, jetzt kein Essen mehr" : "Kein Essen im Inventar";
            inDieHand(slot);
            c.send(new ServerboundUseItemPacket(Hand.MAIN_HAND, sequenz++, yaw, pitch));
            schlafe(1900);
            gegessen++;
        }
        return gegessen + "x gegessen, Hunger jetzt " + essen + "/20";
    }

    private String totemAusruesten() throws InterruptedException {
        int totem = Items.id(Items.TOTEM);
        synchronized (inv) {
            if (inv[45] != null && inv[45].getId() == totem) return "Totem war schon in der zweiten Hand";
        }
        int slot = finde(x -> x == totem, 9, 44);
        if (slot < 0) return "Kein Totem im Inventar";
        klick(0, stateId, slot, ContainerActionType.MOVE_TO_HOTBAR_SLOT, MoveToHotbarAction.OFF_HAND);
        schlafe(600);
        synchronized (inv) {
            return inv[45] != null && inv[45].getId() == totem ? "Totem in die zweite Hand gelegt" : "Totem-Wechsel nicht bestaetigt";
        }
    }

    /** Oeffnet /shop, sucht das Totem (notfalls ueber eine passende Kategorie) und kauft es. */
    private String totemsKaufen(int menge) throws InterruptedException {
        int totem = Items.id(Items.TOTEM);
        int vorher = anzahl(totem);
        int gekauft = 0;
        for (int versuch = 0; versuch < menge + 3 && gekauft < menge && aktiv; versuch++) {
            if (fensterId < 0 || fenster == null) {
                befehl("shop");
                for (int i = 0; i < 40 && fenster == null; i++) schlafe(50);
                if (fenster == null) return gekauft > 0 ? gekauft + " gekauft, Shop ging nicht mehr auf" : "Shop-Fenster ging nicht auf";
            }
            int slot = suche(fenster, totem);
            if (slot < 0) {
                int kat = kategorie(fenster);
                if (kat < 0) return gekauft > 0 ? gekauft + " gekauft" : "Totem im Shop nicht gefunden (Fenster: " + fensterTitel + ")";
                ItemStack[] alt = fenster;
                klick(fensterId, fensterState, kat, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK);
                for (int i = 0; i < 30 && fenster == alt; i++) schlafe(50);
                schlafe(200);
                continue;
            }
            int zeilenVorher = zeilen.size();
            klick(fensterId, fensterState, slot, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK);
            schlafe(700);
            for (String z : zeilen.subList(zeilenVorher, zeilen.size())) {
                Matcher m = GEKAUFT.matcher(z);
                if (m.find() && m.group(2).contains("totem")) gekauft += Integer.parseInt(m.group(1));
                if (z.contains("fehlt dir Geld")) {
                    schliesse();
                    return gekauft > 0 ? gekauft + " gekauft, dann war das Geld alle" : "Nicht genug Geld";
                }
                if (z.contains("nicht genug Platz")) {
                    schliesse();
                    return gekauft > 0 ? gekauft + " gekauft, dann war das Inventar voll" : "Inventar voll";
                }
            }
        }
        schliesse();
        schlafe(300);
        int jetzt = anzahl(totem);
        return (gekauft > 0 ? gekauft : Math.max(0, jetzt - vorher)) + " Totem(s) gekauft, jetzt " + jetzt + " im Inventar";
    }

    private void schliesse() {
        if (fensterId >= 0) c.send(new ServerboundContainerClosePacket(fensterId));
        fensterId = -1;
        fenster = null;
    }

    /** Nur die oberen Fenster-Slots (ohne das eigene Inventar unten). */
    private static int oben(ItemStack[] f) {
        return Math.max(0, f.length - 36);
    }

    private static int suche(ItemStack[] f, int id) {
        for (int i = 0; i < oben(f); i++) if (f[i] != null && f[i].getId() == id) return i;
        return -1;
    }

    private static int kategorie(ItemStack[] f) {
        for (int i = 0; i < oben(f); i++) {
            if (f[i] == null) continue;
            String n = anzeigeName(f[i]) + " " + Items.name(f[i].getId());
            if (KATEGORIE.matcher(n).find()) return i;
        }
        return -1;
    }

    static String anzeigeName(ItemStack s) {
        DataComponents d = s.getDataComponentsPatch();
        if (d != null) {
            Component n = d.get(DataComponentTypes.CUSTOM_NAME);
            if (n == null) n = d.get(DataComponentTypes.ITEM_NAME);
            if (n != null) return Main.text(n);
        }
        return "";
    }

    private String erkunden() throws InterruptedException {
        befehl("shop");
        for (int i = 0; i < 40 && fenster == null; i++) schlafe(50);
        schlafe(500);
        String t = fenster == null ? "Shop ging nicht auf" : "Shop-Fenster gespeichert: " + fensterTitel;
        schliesse();
        return t;
    }

    /** Teleport-Befehl wie /home; wartet auf die neue Position (Essentials hat oft 3 Sekunden Wartezeit). */
    private String teleport(String befehl, String ziel) throws InterruptedException {
        double[] vorher = pos;
        int z0 = zeilen.size();
        befehl(befehl);
        for (int i = 0; i < 140 && pos == vorher; i++) schlafe(50);
        schlafe(400);
        List<String> antwort = new ArrayList<>(zeilen.subList(Math.min(z0, zeilen.size()), zeilen.size()));
        if (pos != vorher) return "Teleportiert " + ziel;
        return antwort.isEmpty() ? "Keine Antwort vom Server" : "Server: " + antwort.get(antwort.size() - 1);
    }

    /** Ein Ausruestungsteil mit Haltbarkeit in Prozent (fuer die App). */
    private static JsonObject gegenstand(ItemStack s, String platz) {
        JsonObject o = new JsonObject();
        o.addProperty("platz", platz);
        String id = Items.name(s.getId());
        o.addProperty("item", id);
        if (s.getAmount() > 1) o.addProperty("anzahl", s.getAmount());
        DataComponents d = s.getDataComponentsPatch();
        Integer schaden = d == null ? null : d.get(DataComponentTypes.DAMAGE);
        Integer max = d == null ? null : d.get(DataComponentTypes.MAX_DAMAGE);
        if (max == null) max = Items.haltbarkeit(id);
        if (max != null && max > 0) o.addProperty("prozent", Math.max(0, Math.round(100.0 * (max - (schaden == null ? 0 : schaden)) / max)));
        if (d != null && d.get(DataComponentTypes.ENCHANTMENTS) != null) o.addProperty("verzaubert", true);
        String n = anzeigeName(s);
        if (!n.isEmpty()) o.addProperty("name", n);
        return o;
    }

    private void merkeFenster() {
        ItemStack[] f = fenster;
        if (f == null || fensterLog.size() > 6) return;
        JsonObject o = new JsonObject();
        o.addProperty("titel", fensterTitel);
        JsonArray items = new JsonArray();
        for (int i = 0; i < oben(f); i++) {
            if (f[i] == null) continue;
            JsonObject j = new JsonObject();
            j.addProperty("slot", i);
            j.addProperty("item", Items.name(f[i].getId()));
            j.addProperty("anzahl", f[i].getAmount());
            String n = anzeigeName(f[i]);
            if (!n.isEmpty()) j.addProperty("name", n);
            DataComponents d = f[i].getDataComponentsPatch();
            List<Component> lore = d == null ? null : d.get(DataComponentTypes.LORE);
            if (lore != null && !lore.isEmpty()) {
                JsonArray l = new JsonArray();
                lore.stream().limit(4).forEach(x -> l.add(Main.text(x)));
                j.add("lore", l);
            }
            items.add(j);
        }
        o.add("items", items);
        synchronized (fensterLog) { fensterLog.add(o); }
    }

    /** Baut ringsum zwei Bloecke hoch und ein Dach (10 Bloecke). */
    private String einbauen() throws InterruptedException {
        if (pos == null) return "Position unbekannt";
        int slot = finde(Items.BAUSTEINE);
        if (slot < 0) return "Keine Bausteine im Inventar (z. B. Cobblestone, Erde, Holz)";
        String stein = Items.name(inv[slot].getId());
        inDieHand(slot);
        int x = (int) Math.floor(pos[0]), y = (int) Math.floor(pos[1]), z = (int) Math.floor(pos[2]);
        int[][] seiten = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int gesetzt = 0;
        for (int[] s : seiten) {
            gesetzt += setze(Vector3i.from(x + s[0], y - 1, z + s[1]), Direction.UP) ? 1 : 0;
            gesetzt += setze(Vector3i.from(x + s[0], y, z + s[1]), Direction.UP) ? 1 : 0;
        }
        gesetzt += setze(Vector3i.from(x + 1, y + 1, z), Direction.UP) ? 1 : 0;
        gesetzt += setze(Vector3i.from(x + 1, y + 2, z), Direction.WEST) ? 1 : 0;
        return "Eingebaut mit " + stein + " (" + gesetzt + " Bloecke versucht, besetzte Stellen bleiben wie sie sind)";
    }

    /** Klickt mit dem Block in der Hand auf eine Seite des Blocks bei p (der neue Block entsteht daneben). */
    private boolean setze(Vector3i p, Direction seite) throws InterruptedException {
        double ex = pos[0], ey = pos[1] + 1.62, ez = pos[2];
        double tx = p.getX() + 0.5, ty = p.getY() + (seite == Direction.UP ? 1.0 : 0.5), tz = p.getZ() + (seite == Direction.WEST ? 0.0 : 0.5);
        double dx = tx - ex, dy = ty - ey, dz = tz - ez;
        float ya = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pi = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        c.send(new ServerboundMovePlayerRotPacket(true, false, ya, pi));
        float cx = seite == Direction.WEST ? 0f : 0.5f, cy = seite == Direction.UP ? 1f : 0.5f;
        c.send(new ServerboundUseItemOnPacket(p, seite, Hand.MAIN_HAND, cx, cy, 0.5f, false, false, sequenz++));
        schlafe(220);
        return true;
    }

    // ------------------------------------------------------------------ Ergebnis

    private void werteAus() {
        if (leben >= 0) lauf.addProperty("leben", leben);
        if (essen >= 0) lauf.addProperty("essen", essen);
        if (level >= 0) lauf.addProperty("level", level);
        if (statistiken != null) lauf.add("statistiken", statistiken);
        if (xpFortschritt >= 0) lauf.addProperty("xpFortschritt", Math.round(xpFortschritt * 100));
        if (saettigung >= 0) lauf.addProperty("saettigung", saettigung);
        if (welt != null) lauf.addProperty("welt", welt);
        if (spielmodus != null) lauf.addProperty("spielmodus", spielmodus);
        if (letzterTod != null) lauf.addProperty("letzterTod", letzterTod);
        if (schwierigkeit != null) lauf.addProperty("schwierigkeit", schwierigkeit);
        if (tageszeit >= 0) lauf.addProperty("tageszeit", tageszeit % 24000);
        if (pos != null) lauf.addProperty("wetter", gewitter ? "gewitter" : regen ? "regen" : "klar");
        if (!effekte.isEmpty()) {
            JsonArray ef = new JsonArray();
            effekte.forEach((n, w) -> {
                JsonObject o = new JsonObject();
                o.addProperty("name", n);
                o.addProperty("stufe", w[0] + 1);
                o.addProperty("sekunden", w[1] < 0 ? -1 : w[1] / 20);
                ef.add(o);
            });
            lauf.add("effekte", ef);
        }
        if (pos != null) {
            JsonArray p = new JsonArray();
            for (double d : pos) p.add(Math.round(d));
            lauf.add("position", p);
        }
        if (inventarDa) {
            Map<String, Integer> zaehl = new TreeMap<>();
            synchronized (inv) {
                for (int i = 5; i < 46; i++) if (inv[i] != null) zaehl.merge(Items.name(inv[i].getId()), inv[i].getAmount(), Integer::sum);
                if (inv[45] != null) lauf.addProperty("zweiteHand", Items.name(inv[45].getId()));
            }
            JsonObject in = new JsonObject();
            zaehl.forEach(in::addProperty);
            lauf.add("inventar", in);
            JsonArray ruestung = new JsonArray();
            String[] teile = {"kopf", "brust", "beine", "fuesse"};
            synchronized (inv) {
                for (int i = 5; i <= 8; i++) if (inv[i] != null) ruestung.add(gegenstand(inv[i], teile[i - 5]));
                if (inv[45] != null) ruestung.add(gegenstand(inv[45], "zweiteHand"));
                if (handSlot >= 0 && handSlot <= 8 && inv[36 + handSlot] != null) lauf.add("hand", gegenstand(inv[36 + handSlot], "hand"));
            }
            lauf.add("ruestung", ruestung);
        }
        synchronized (fensterLog) { if (!fensterLog.isEmpty()) lauf.add("fenster", fensterLog); }
        JsonArray roh = lauf.has("nachrichten") ? lauf.getAsJsonArray("nachrichten") : new JsonArray();
        Map<Integer, JsonObject> top = new TreeMap<>();
        for (String z : zeilen) {
            roh.add(z);
            Matcher post = POST.matcher(z);
            if (post.find()) lauf.addProperty("post", Integer.parseInt(post.group(1)));
            else if (z.toLowerCase().contains("no new mail")) lauf.addProperty("post", 0);
            Matcher m = Main.KONTOSTAND.matcher(z);
            if (m.find()) lauf.addProperty("kontostand", Main.betrag(m.group(1)));
            m = Main.RANG.matcher(z);
            if (m.find()) {
                JsonObject r = new JsonObject();
                r.addProperty("platz", Integer.parseInt(m.group(1)));
                r.addProperty("name", m.group(2));
                r.addProperty("geld", Main.betrag(m.group(3)));
                top.put(Integer.parseInt(m.group(1)), r);
            }
        }
        lauf.add("nachrichten", roh);
        if (!top.isEmpty()) {
            JsonArray t = new JsonArray();
            top.values().forEach(t::add);
            lauf.add("topgeld", t);
        }
        JsonArray sp = new JsonArray();
        synchronized (spieler) { spieler.stream().filter(n -> !n.equalsIgnoreCase(profil.getName())).forEach(sp::add); }
        if (!sp.isEmpty()) lauf.add("online", sp);
        // Monster in der Naehe merken (nur Info fuer die App)
        if (pos != null) {
            JsonArray gefahr = new JsonArray();
            for (Object[] w : wesen) {
                double dx = (double) w[1] - pos[0], dy = (double) w[2] - pos[1], dz = (double) w[3] - pos[2];
                double a = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (Main.GEFAEHRLICH.contains((String) w[0]) && a < 16) gefahr.add(((String) w[0]).toLowerCase() + " " + Math.round(a) + "m");
            }
            if (!gefahr.isEmpty()) lauf.add("gefahrInDerNaehe", gefahr);
            JsonArray nah = new JsonArray();
            for (Object[] w : wesen) {
                if (!"PLAYER".equals(w[0]) || w.length < 5) continue;
                double dx = (double) w[1] - pos[0], dy = (double) w[2] - pos[1], dz = (double) w[3] - pos[2];
                String n = namen.getOrDefault((UUID) w[4], "Jemand");
                if (!n.equalsIgnoreCase(profil.getName())) nah.add(n + " " + Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)) + "m");
            }
            if (!nah.isEmpty()) lauf.add("spielerNah", nah);
        }
    }

    static List<Auftrag> kopie(List<Auftrag> a) {
        return new ArrayList<>(a);
    }
}
