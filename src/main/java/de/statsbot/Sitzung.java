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
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
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
        String status = "offen", ergebnis = "", zeit = "";

        Auftrag(String id, String art, int anzahl) {
            this.id = id;
            this.art = art;
            this.anzahl = anzahl;
        }
    }

    private static final Pattern GEKAUFT = Pattern.compile("^\\[Shop\\] Gekauft: (\\d+)x? (.+?) f(?:ü|ue)r");
    private static final Pattern POST = Pattern.compile("(?i)you have (\\d+) (?:new )?(?:messages?|mails?)");

    private final String host;
    private final int port;
    private final MinecraftProfile profil;
    private final String name;
    private final String token;
    /** Bloecke, die der Server gemeldet hat (Position -> Blockzustand, 0 = Luft); zum Pruefen beim Einbauen. */
    private final Map<Vector3i, Integer> bloecke = new ConcurrentHashMap<>();
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
        this.name = profil.getName();
        this.token = token;
        this.auftraege = auftraege;
        this.lauf = lauf;
    }

    /** Nur zum Testen auf einem eigenen Server ohne Anmeldung (online-mode=false). Auf echten Servern geht das nicht. */
    Sitzung(String host, int port, String testName, List<Auftrag> auftraege, JsonObject lauf) {
        this.host = host;
        this.port = port;
        this.profil = null;
        this.name = testName;
        this.token = null;
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
            .setProtocol(profil == null ? new MinecraftProtocol(name) : new MinecraftProtocol(new GameProfile(profil.getId(), profil.getName()), token))
            .create();
        if (profil != null) c.setFlag(MinecraftConstants.SESSION_SERVICE_KEY, new SessionService());
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
            // Neue Position (Werte koennen relativ zur alten sein); seit Minecraft 26 muss die Bestaetigung
            // die Position mitschicken, sonst haelt der Server den Spieler fuer zu schnell und setzt ihn dauernd zurueck
            double[] alt = pos;
            var rel = pp.getRelatives();
            double x = pp.getPosition().getX(), y = pp.getPosition().getY(), z = pp.getPosition().getZ();
            if (alt != null) {
                if (rel.contains(PositionElement.X)) x += alt[0];
                if (rel.contains(PositionElement.Y)) y += alt[1];
                if (rel.contains(PositionElement.Z)) z += alt[2];
            }
            float yr = pp.getYRot() + (rel.contains(PositionElement.Y_ROT) ? yaw : 0);
            float xr = pp.getXRot() + (rel.contains(PositionElement.X_ROT) ? pitch : 0);
            pos = new double[]{x, y, z};
            yaw = yr;
            pitch = xr;
            c.send(new ServerboundAcceptTeleportationPacket(pp.getId(), x, y, z, yr, xr));
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
        } else if (p instanceof ClientboundBlockUpdatePacket bu) {
            bloecke.put(bu.getEntry().getPosition(), bu.getEntry().getBlock());
        } else if (p instanceof ClientboundSectionBlocksUpdatePacket su) {
            for (BlockChangeEntry e : su.getEntries()) bloecke.put(e.getPosition(), e.getBlock());
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
                    // "kaufen:golden_carrot" usw.: beliebiges Item aus dem Shop (hoechstens 64, max. 5000 $ pro Stueck, 25.000 $ pro Auftrag)
                    default -> a.art.startsWith("kaufen:")
                        ? kaufe(a.art.substring(7), Math.max(1, Math.min(64, a.anzahl)), 5000, 25000)
                        : "Unbekannter Befehl";
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

    private String totemsKaufen(int menge) throws InterruptedException {
        return kaufe(Items.TOTEM, menge, 3000, 16 * 3000);
    }

    /**
     * Kauft [menge] Stueck von [name] (Minecraft-Kennung) im /shop. Sicher gegen Fehlkaeufe:
     * im Hauptmenue nur Kategorien, auf Kategorie-Seiten nur genau das gesuchte Item oder "naechste Seite",
     * im Fenster "Menge waehlen" wird die Menge genau eingestellt und der Preis geprueft.
     * Jeder Kauf muss bestaetigt sein (Chat-Meldung oder mehr im Inventar), sonst hoert der Bot sofort auf.
     */
    private String kaufe(String name, int menge, double maxProStueck, double maxGesamt) throws InterruptedException {
        int id = Items.id(name);
        if (id < 0) return "Unbekanntes Item: " + name;
        int gekauft = 0;
        double ausgegeben = 0;
        for (int runde = 0; runde < 8 && gekauft < menge && aktiv; runde++) {
            if (!findeSeite(id)) return ende(gekauft, gekauft == 0 ? "im Shop nicht gefunden" : null);
            ItemStack[] f = fenster;
            int slot = suche(f, id);
            int zeilenVorher = zeilen.size();
            int imFenster = anzahlUnten(f, id);
            int stueck = Math.min(64, menge - gekauft);
            klick(fensterId, fensterState, slot, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK);
            schlafe(800);
            if (fenster != null && fenster != f && fensterTitel.toLowerCase().contains("menge")) {
                String fehler = mengeEinstellen(stueck, id);
                if (fehler != null) return ende(gekauft, fehler);
                double preis = gesamtPreis(stueck);
                if (preis == -2) return ende(gekauft, "Menge passt nicht");
                if (preis < 0) return ende(gekauft, "Preis nicht lesbar");
                if (preis > stueck * maxProStueck || ausgegeben + preis > maxGesamt) return ende(gekauft, "Preis zu hoch ($" + Math.round(preis) + ")");
                int kaufen = slotMitName(fenster, "kaufen");
                if (kaufen < 0) return ende(gekauft, "Kaufen-Knopf nicht gefunden");
                imFenster = anzahlUnten(fenster, id);
                zeilenVorher = zeilen.size();
                klick(fensterId, fensterState, kaufen, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK);
                schlafe(900);
                ausgegeben += preis;
            }
            int neu = 0;
            for (String z : zeilen.subList(Math.min(zeilenVorher, zeilen.size()), zeilen.size())) {
                Matcher m = GEKAUFT.matcher(z);
                if (m.find() && m.group(2).toLowerCase().strip().replace(' ', '_').contains(name)) neu += Integer.parseInt(m.group(1));
                if (z.contains("fehlt dir Geld")) return ende(gekauft, "nicht genug Geld");
                if (z.contains("nicht genug Platz")) return ende(gekauft, "Inventar voll");
            }
            ItemStack[] danach = fenster;
            if (neu == 0 && danach != null) neu = Math.max(0, anzahlUnten(danach, id) - imFenster);
            // Kein bestaetigter Kauf (z. B. ein unbekanntes Fenster): lieber sofort aufhoeren
            if (neu == 0) return ende(gekauft, "Kauf nicht bestaetigt (Fenster: " + fensterTitel + ")");
            gekauft += neu;
            schliesse();
            schlafe(300);
        }
        return ende(gekauft, null);
    }

    /** Oeffnet den Shop und sucht die Seite mit dem Item: passende Kategorie zuerst, dann alle anderen. */
    private boolean findeSeite(int id) throws InterruptedException {
        if (!oeffneShop()) return false;
        if (suche(fenster, id) >= 0) return true;
        for (int k : kategorien(fenster, Items.name(id))) {
            if (!wechsle(k)) {
                if (!oeffneShop()) return false;
                continue;
            }
            for (int seite = 0; seite < 3; seite++) {
                if (suche(fenster, id) >= 0) return true;
                int w = weiterKnopf(fenster);
                if (w < 0 || !wechsle(w)) break;
            }
            if (!oeffneShop()) return false;
        }
        return false;
    }

    /** Kategorien im Hauptmenue ("Klick: oeffnen"), die zum Item passende zuerst. */
    private static List<Integer> kategorien(ItemStack[] f, String item) {
        List<Integer> liste = new ArrayList<>();
        for (int i = 0; i < oben(f); i++) {
            if (f[i] == null) continue;
            if (lore(f[i]).stream().anyMatch(z -> z.toLowerCase().contains("öffnen") || z.toLowerCase().contains("oeffnen"))) liste.add(i);
        }
        if (liste.isEmpty()) {
            int k = kategorie(f);
            if (k >= 0) liste.add(k);
            return liste;
        }
        String wunsch = item.matches(".*(carrot|apple|bread|beef|porkchop|chicken|mutton|salmon|cod|potato|stew|pie|cookie|melon|berries|kelp|soup|rabbit).*") ? "essen|food|nahrung"
            : item.matches(".*(totem|sword|axe|pickaxe|shovel|hoe|helmet|chestplate|leggings|boots|shield|bow|arrow|trident|mace|pearl|bucket|bottle|charge|diamond|netherite).*") ? "ausrüstung|ausruestung|kampf|waffe|combat|equipment"
            : item.matches(".*(redstone|piston|repeater|comparator|hopper|observer|lever|button|rail|dispenser|dropper).*") ? "redstone"
            : item.matches(".*(sapling|propagule).*") ? "setzling"
            : item.matches(".*(template).*") ? "vorlage"
            : "blöcke|bloecke|block";
        liste.sort((x, y) -> Boolean.compare(!anzeigeName(f[x]).toLowerCase().matches("(?s).*(" + wunsch + ").*"),
            !anzeigeName(f[y]).toLowerCase().matches("(?s).*(" + wunsch + ").*")));
        return liste;
    }

    private static final Pattern MENGE = Pattern.compile("(?i)du kaufst:\\s*(\\d+)x");
    private static final Pattern KAUFPREIS = Pattern.compile("(?i)(\\d+)x\\s*f(?:ü|ue)r\\s*\\$([\\d.,]+[KkMmBb]?)");

    /** Stellt im Fenster "Menge waehlen" mit den +/- Knoepfen genau [n] ein. null = geklappt, sonst der Grund. */
    private String mengeEinstellen(int n, int id) throws InterruptedException {
        for (int schritt = 0; schritt < 40 && aktiv; schritt++) {
            int jetzt = mengeImFenster(id);
            if (jetzt < 0) return "Menge im Shop nicht lesbar";
            if (jetzt == n) return null;
            int diff = n - jetzt;
            String name = diff >= 64 ? "+64" : diff >= 16 ? "+16" : diff > 0 ? "+1" : diff <= -64 ? "-64" : diff <= -16 ? "-16" : "-1";
            int knopf = slotMitName(fenster, name);
            if (knopf < 0) knopf = slotMitName(fenster, diff > 0 ? "+1" : "-1");
            if (knopf < 0) return "Mengen-Knoepfe nicht gefunden";
            klick(fensterId, fensterState, knopf, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK);
            for (int i = 0; i < 20 && mengeImFenster(id) == jetzt; i++) schlafe(50);
        }
        return "Menge liess sich nicht einstellen";
    }

    private int mengeImFenster(int id) {
        ItemStack[] f = fenster;
        if (f == null) return -1;
        for (int i = 0; i < oben(f); i++) {
            if (f[i] == null || f[i].getId() != id) continue;
            for (String z : lore(f[i])) {
                Matcher m = MENGE.matcher(z);
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        }
        return -1;
    }

    /** Gesamtpreis laut Kaufen-Knopf ("Nx fuer $Preis"). -2 = Menge passt nicht, -1 = nicht lesbar. */
    private double gesamtPreis(int n) {
        int k = slotMitName(fenster, "kaufen");
        if (k < 0) return -1;
        for (String z : lore(fenster[k])) {
            Matcher m = KAUFPREIS.matcher(z);
            if (!m.find()) continue;
            if (Integer.parseInt(m.group(1)) != n) return -2;
            return Main.betrag(m.group(2));
        }
        return -1;
    }

    private static int slotMitName(ItemStack[] f, String name) {
        if (f == null) return -1;
        for (int i = 0; i < oben(f); i++) if (f[i] != null && anzeigeName(f[i]).strip().equalsIgnoreCase(name)) return i;
        return -1;
    }

    private static List<String> lore(ItemStack s) {
        List<String> r = new ArrayList<>();
        DataComponents d = s == null ? null : s.getDataComponentsPatch();
        List<Component> l = d == null ? null : d.get(DataComponentTypes.LORE);
        if (l != null) for (Component c : l) r.add(Main.text(c));
        return r;
    }

    private String ende(int gekauft, String grund) throws InterruptedException {
        schliesse();
        schlafe(300);
        String teil = gekauft > 0 ? gekauft + " Stueck gekauft" : "Nichts gekauft";
        return teil + (grund != null ? " (" + grund + ")" : "");
    }

    private boolean oeffneShop() throws InterruptedException {
        schliesse();
        befehl("shop");
        for (int i = 0; i < 40 && fenster == null; i++) schlafe(50);
        schlafe(300);
        return fenster != null;
    }

    /** Klickt eine Kategorie oder "naechste Seite" und wartet auf den neuen Inhalt. */
    private boolean wechsle(int slot) throws InterruptedException {
        ItemStack[] alt = fenster;
        String vorher = inhalt(alt);
        klick(fensterId, fensterState, slot, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK);
        for (int i = 0; i < 40 && (fenster == null || inhalt(fenster).equals(vorher)); i++) schlafe(50);
        schlafe(250);
        return fenster != null && !inhalt(fenster).equals(vorher);
    }

    /** Kurzer Fingerabdruck der oberen Slots, um einen echten Seitenwechsel zu erkennen. */
    private static String inhalt(ItemStack[] f) {
        if (f == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < oben(f); i++) b.append(f[i] == null ? "-" : f[i].getId()).append(',');
        return b.toString();
    }

    /** So viele Stueck des Items im eigenen Inventar-Teil des offenen Fensters (unten). */
    private static int anzahlUnten(ItemStack[] f, int id) {
        int n = 0;
        for (int i = oben(f); i < f.length; i++) if (f[i] != null && f[i].getId() == id) n += f[i].getAmount();
        return n;
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

    /** Beste Kategorie im Hauptmenue: Totem, dann Kampf/Waffen, dann Ausruestung, dann Sonstiges. -1 = keine. */
    private static int kategorie(ItemStack[] f) {
        int beste = -1, rang = 99;
        for (int i = 0; i < oben(f); i++) {
            if (f[i] == null) continue;
            String n = (anzeigeName(f[i]) + " " + Items.name(f[i].getId())).toLowerCase();
            int r = n.contains("totem") ? 0
                : n.matches("(?s).*(kampf|combat|waffe).*") ? 1
                : n.matches("(?s).*(ausrüstung|ausruestung|rüstung|ruestung|equipment|gear).*") ? 2
                : n.matches("(?s).*(verschied|sonstig|misc|spezial|special|selten|rare|magie).*") ? 3 : 99;
            if (r < rang) {
                rang = r;
                beste = i;
            }
        }
        return beste;
    }

    /** Knopf fuer die naechste Seite auf einer Shop-Unterseite. -1 = keiner. */
    private static int weiterKnopf(ItemStack[] f) {
        for (int i = 0; i < oben(f); i++) {
            if (f[i] == null) continue;
            String n = anzeigeName(f[i]).toLowerCase();
            if (n.matches("(?s).*(weiter|nächste|naechste|next|seite \\d|→|»).*")) return i;
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

    /** Shop ansehen (nur anschauen, nichts kaufen): Hauptmenue und die Kategorie, in der das Totem sein muesste. */
    private String erkunden() throws InterruptedException {
        if (!oeffneShop()) return "Shop ging nicht auf";
        String haupt = fensterTitel;
        String mehr = "";
        int kat = kategorie(fenster);
        if (kat >= 0) {
            String name = anzeigeName(fenster[kat]);
            if (wechsle(kat)) {
                int t = suche(fenster, Items.id(Items.TOTEM));
                mehr = ", in \"" + name + "\" " + (t >= 0 ? "liegt ein Totem" : "kein Totem auf der ersten Seite");
            }
        }
        schliesse();
        return "Shop gespeichert: " + haupt + mehr;
    }

    /** Teleport-Befehl wie /home; wartet auf die neue Position (Essentials hat oft 3 Sekunden Wartezeit). */
    private String teleport(String befehl, String ziel) throws InterruptedException {
        double[] vorher = pos;
        int z0 = zeilen.size();
        befehl(befehl);
        // Nur ein echter Ortswechsel zaehlt (kleine Korrekturen vom Server nicht); bei "unbekannter Befehl" sofort aufhoeren
        for (int i = 0; i < 140 && !weg(vorher); i++) {
            schlafe(50);
            if (zeilen.subList(Math.min(z0, zeilen.size()), zeilen.size()).stream().anyMatch(z -> z.toLowerCase().matches(".*(unknown|unbekannt|incomplete|no permission|keine berechtigung).*"))) break;
        }
        schlafe(400);
        List<String> antwort = new ArrayList<>(zeilen.subList(Math.min(z0, zeilen.size()), zeilen.size()));
        if (weg(vorher)) return "Teleportiert " + ziel;
        return antwort.isEmpty() ? "Keine Antwort vom Server" : "Ging nicht. Server: " + antwort.get(antwort.size() - 1);
    }

    /** Mehr als 2 Bloecke von der alten Position entfernt? */
    private boolean weg(double[] vorher) {
        double[] jetzt = pos;
        if (vorher == null || jetzt == null) return false;
        double dx = jetzt[0] - vorher[0], dy = jetzt[1] - vorher[1], dz = jetzt[2] - vorher[2];
        return dx * dx + dy * dy + dz * dz > 4;
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
        // Jeder Klick setzt den neuen Block auf die angeklickte Seite; "ziele" sind die Stellen, wo Bloecke entstehen sollen
        List<Vector3i> ziele = new ArrayList<>();
        for (int[] s : seiten) {
            setze(Vector3i.from(x + s[0], y - 1, z + s[1]), Direction.UP);
            ziele.add(Vector3i.from(x + s[0], y, z + s[1]));
            setze(Vector3i.from(x + s[0], y, z + s[1]), Direction.UP);
            ziele.add(Vector3i.from(x + s[0], y + 1, z + s[1]));
        }
        // Dach: erst ein Hilfsblock schraeg oben, dann an dessen Seite der Block direkt ueber dem Kopf.
        // Kurz warten, bis der Hilfsblock beim Server steht (sonst klappt das Dach manchmal nicht), notfalls zweiter Versuch.
        Vector3i hilfe = Vector3i.from(x + 1, y + 2, z), dach = Vector3i.from(x, y + 2, z);
        setze(Vector3i.from(x + 1, y + 1, z), Direction.UP);
        ziele.add(hilfe);
        schlafe(500);
        setze(hilfe, Direction.WEST);
        ziele.add(dach);
        schlafe(700);
        if (!zu(dach)) {
            setze(hilfe, Direction.WEST);
            schlafe(700);
        }
        // Der Server meldet nach jedem Klick, was an der Stelle steht: so sieht man, welche Plaetze wirklich zu sind
        long dicht = ziele.stream().filter(this::zu).count();
        if (dicht == 0) return "Konnte nicht einbauen (kein Boden daneben oder keine Antwort vom Server)";
        return (dicht == 10 ? "Eingebaut: alle 10 Plaetze sind zu" : dicht + " von 10 Plaetzen sind zu") + " (mit " + stein + ")"
            + (zu(dach) ? ", Dach ist drauf" : ", ohne Dach");
    }

    /** Steht an der Stelle laut Server ein Block (keine Luft)? */
    private boolean zu(Vector3i q) {
        Integer b = bloecke.get(q);
        return b != null && b != 0;
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
        synchronized (spieler) { spieler.stream().filter(n -> !n.equalsIgnoreCase(name)).forEach(sp::add); }
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
                if (!n.equalsIgnoreCase(name)) nah.add(n + " " + Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)) + "m");
            }
            if (!nah.isEmpty()) lauf.add("spielerNah", nah);
        }
    }

    static List<Auftrag> kopie(List<Auftrag> a) {
        return new ArrayList<>(a);
    }
}
