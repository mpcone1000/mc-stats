package de.statsbot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Item-Nummern von Minecraft 26.3 (aus dem offiziellen Daten-Generator, siehe items.txt). */
final class Items {

    private static final Map<Integer, String> NAME = new HashMap<>();
    private static final Map<String, Integer> ID = new HashMap<>();

    static {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(Items.class.getResourceAsStream("/items.txt"), StandardCharsets.UTF_8))) {
            String z;
            while ((z = r.readLine()) != null) {
                int leer = z.indexOf(' ');
                if (leer < 0) continue;
                int id = Integer.parseInt(z.substring(0, leer));
                String n = z.substring(leer + 1).strip();
                NAME.put(id, n);
                ID.put(n, id);
            }
        } catch (Exception e) {
            throw new IllegalStateException("items.txt fehlt", e);
        }
    }

    static String name(int id) {
        return NAME.getOrDefault(id, "#" + id);
    }

    static int id(String name) {
        return ID.getOrDefault(name, -1);
    }

    static final String TOTEM = "totem_of_undying";

    /** Essen in der Reihenfolge, in der der Bot es nimmt (guenstiges zuerst, goldene Aepfel nie). */
    static final List<String> ESSEN = List.of("cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken", "cooked_salmon",
        "cooked_cod", "baked_potato", "bread", "cooked_rabbit", "pumpkin_pie", "mushroom_stew", "rabbit_stew", "beetroot_soup",
        "carrot", "apple", "melon_slice", "sweet_berries", "glow_berries", "potato", "beetroot", "cookie", "dried_kelp", "golden_carrot");

    /** Bloecke zum Einbauen (nichts Wertvolles). */
    static final List<String> BAUSTEINE = List.of("cobblestone", "cobbled_deepslate", "stone", "deepslate", "dirt", "netherrack",
        "andesite", "diorite", "granite", "tuff", "blackstone", "sandstone", "oak_planks", "spruce_planks", "birch_planks",
        "jungle_planks", "acacia_planks", "dark_oak_planks", "mangrove_planks", "cherry_planks", "bamboo_planks", "stone_bricks",
        "bricks", "end_stone", "basalt", "smooth_stone", "oak_log", "spruce_log", "birch_log", "glass", "white_wool", "sand", "gravel");

    private Items() {}
}
