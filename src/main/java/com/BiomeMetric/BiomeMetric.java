package com.Bit;

import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Sapling;


import java.io.File;
import java.io.IOException;
import java.util.*;

public class BiomeMetric extends JavaPlugin implements Listener {

    private File cropsFile;
    private FileConfiguration cropsConfig;
    private File biomesFile;
    private FileConfiguration biomesConfig;

    private final Map<String, CropData> cropCache = new HashMap<>();
    private final Map<String, Set<String>> biomeGroups = new HashMap<>();

    private long updateIntervalTicks = 20L * 60;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        createFiles();
        loadCrops();
        loadBiomeConfig();
        setupBiomeGroups();

        startGrowthTask();

        getLogger().info("BiomeMetric enabled");
    }

    @Override
    public void onDisable() {
        saveCrops();
        getLogger().info("BiomeMetric disabled.");
    }

    // File setup
    private void createFiles() {
        getDataFolder().mkdirs();

        cropsFile = new File(getDataFolder(), "crops.yml");
        if (!cropsFile.exists()) {
            try {
                cropsFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        cropsConfig = YamlConfiguration.loadConfiguration(cropsFile);

        biomesFile = new File(getDataFolder(), "biomes.yml");
        if (!biomesFile.exists()) {
            saveResource("biomes.yml", false);
        }
        biomesConfig = YamlConfiguration.loadConfiguration(biomesFile);
    }

    // Biome grouping
    private void setupBiomeGroups() {
        biomeGroups.clear();
        
        if (biomesConfig.contains("biome-groups")) {
            for (String groupName : biomesConfig.getConfigurationSection("biome-groups").getKeys(false)) {
                List<String> biomes = biomesConfig.getStringList("biome-groups." + groupName);
                biomeGroups.put(groupName, new HashSet<>(biomes));
            }
        } else {
            getLogger().warning("No 'biome-groups' section found in biomes.yml! Biome growth will not work properly.");
        }
        
        getLogger().info("Loaded " + biomeGroups.size() + " biome groups from configuration.");
    }

    // Modify onPlant method to handle new plant types
    @EventHandler
    public void onPlant(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Material type = block.getType();

        if (!isCrop(type)) return;

        // Handle stacking plants
        if (isStackingCrop(type)) {
            Block below = block.getRelative(0, -1, 0);
            if (below.getType() == type || 
                (isKelp(type) && below.getType() == Material.KELP) ||
                (isKelp(type) && below.getType() == Material.KELP_PLANT)) {
                return;
            }
        }

        // Skip attached stems
        if (type == Material.ATTACHED_PUMPKIN_STEM || type == Material.ATTACHED_MELON_STEM) {
            return;
        }

        String key = key(block);
        cropCache.put(key, new CropData(type, System.currentTimeMillis(), 1));
        cropsConfig.set("crops." + key + ".type", type.name());
        cropsConfig.set("crops." + key + ".planted", System.currentTimeMillis());

        if (isStackingCrop(type)) {
            cropsConfig.set("crops." + key + ".height", 1);
        }

        saveFile();
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        
        // Don't track fruits, logs, or leaves
        if (type == Material.PUMPKIN || type == Material.MELON ||
            type.name().contains("_LOG") || type.name().contains("_WOOD") ||
            type.name().contains("_LEAVES")) {
            return;
        }
        
        if (!isCrop(type)) return;
    
        if (isStackingCrop(type)) {
            Block base = findBaseBlock(block);
            String key = key(base);
            cropCache.remove(key);
            cropsConfig.set("crops." + key, null);
            saveFile();
        } else {
            String key = key(block);
            cropCache.remove(key);
            cropsConfig.set("crops." + key, null);
            saveFile();
        }
    }

    // Growth simulation
    private void startGrowthTask() {
        long seconds = biomesConfig.getLong("update-interval", 60);
        updateIntervalTicks = seconds * 20L;

        new BukkitRunnable() {
            @Override
            public void run() {
                simulateGrowth();
            }
        }.runTaskTimer(this, updateIntervalTicks, updateIntervalTicks);
    }

    private void simulateGrowth() {
        for (Map.Entry<String, CropData> entry : cropCache.entrySet()) {
            String key = entry.getKey();
            CropData data = entry.getValue();
            String[] parts = key.split(":");
            if (parts.length < 4) continue;

            World world = Bukkit.getWorld(parts[0]);
            if (world == null) continue;

            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            Block block = world.getBlockAt(x, y, z);

            if (isStackingCrop(data.type)) {
                if (isBamboo(data.type)) {
                    simulateBambooGrowth(block, data, key);
                } else if (isKelp(data.type)) {
                    simulateKelpGrowth(block, data, key);
                } else {
                    simulateStackingGrowth(block, data, key);
                }
            } 
            else if (isSapling(data.type)) {
                simulateSaplingGrowth(block, data);
            }
            else if (data.type == Material.COCOA && block.getBlockData() instanceof org.bukkit.block.data.type.Cocoa cocoa) {
                simulateCocoaGrowth(block, cocoa, data);
            }
            else if ((data.type == Material.MELON_STEM || data.type == Material.PUMPKIN_STEM) && 
                     block.getBlockData() instanceof Ageable ageable) {
                simulateStemGrowth(block, ageable, data);
            }
            else if (block.getBlockData() instanceof Ageable ageable) {
                simulateAgeableGrowth(block, ageable, data);
            }
        }
    }

    private void simulateSaplingGrowth(Block saplingBlock, CropData data) {
        if (saplingBlock.getType() != data.type) return;
        
        Biome biome = saplingBlock.getBiome();
        long growTime = getGrowTimeSeconds(biome, data.type) * 1000L;
        
        if (growTime <= 0) return;
        
        long elapsed = System.currentTimeMillis() - data.planted;
        
        if (elapsed >= growTime) {
            if (saplingBlock.getBlockData() instanceof Sapling sapling) {
                // Generate tree based on sapling type
                TreeType treeType = getTreeType(data.type);
                if (treeType != null) {
                    // Remove the sapling first to prevent conflicts
                    saplingBlock.setType(Material.AIR);

                    // Generate the tree
                    if (saplingBlock.getWorld().generateTree(saplingBlock.getLocation(), treeType)) {
                        // Successfully grew - remove from tracking
                        cropCache.remove(key(saplingBlock));
                        cropsConfig.set("crops." + key(saplingBlock), null);
                        saveFile();
                    } else {
                        // Failed to grow - put sapling back
                        saplingBlock.setType(data.type);
                        saplingBlock.setBlockData(sapling);
                    }
                }
            }
        }
    }

    private void simulateBambooGrowth(Block baseBlock, CropData data, String key) {
        if (baseBlock.getType() != Material.BAMBOO) return;

        Biome biome = baseBlock.getBiome();
        long growInterval = getGrowIntervalSeconds(biome, Material.BAMBOO) * 1000L;
        int maxHeight = getMaxHeight(biome, Material.BAMBOO);

        if (growInterval <= 0) return;

        long elapsed = System.currentTimeMillis() - data.planted;
        int targetHeight = Math.min((int) (elapsed / growInterval) + 1, maxHeight);

        if (targetHeight > data.currentHeight) {
            Block topBlock = baseBlock;
            int currentHeight = 1;

            while (currentHeight < data.currentHeight) {
                Block above = topBlock.getRelative(0, 1, 0);
                if (above.getType() == Material.BAMBOO) {
                    topBlock = above;
                    currentHeight++;
                } else {
                    break;
                }
            }

            while (currentHeight < targetHeight) {
                Block above = topBlock.getRelative(0, 1, 0);
                if (above.getType() == Material.AIR) {
                    above.setType(Material.BAMBOO);
                    topBlock = above;
                    currentHeight++;
                    data.currentHeight = currentHeight;
                } else {
                    break;
                }
            }

            if (data.currentHeight != cropsConfig.getInt("crops." + key + ".height")) {
                cropsConfig.set("crops." + key + ".height", data.currentHeight);
                saveFile();
            }
        }
    }

    private void simulateKelpGrowth(Block baseBlock, CropData data, String key) {
        // Find the base kelp block
        Block baseKelp = findBaseBlock(baseBlock);
        if (baseKelp.getType() != Material.KELP) return;

        Biome biome = baseKelp.getBiome();
        long growInterval = getGrowIntervalSeconds(biome, Material.KELP) * 1000L;
        int maxHeight = getMaxHeight(biome, Material.KELP);

        if (growInterval <= 0) return;

        long elapsed = System.currentTimeMillis() - data.planted;
        int targetHeight = Math.min((int) (elapsed / growInterval) + 1, maxHeight);

        if (targetHeight > data.currentHeight) {
            Block topBlock = baseKelp;
            int currentHeight = 1;

            while (currentHeight < data.currentHeight) {
                Block above = topBlock.getRelative(0, 1, 0);
                if (above.getType() == Material.KELP || above.getType() == Material.KELP_PLANT) {
                    topBlock = above;
                    currentHeight++;
                } else {
                    break;
                }
            }

            while (currentHeight < targetHeight && currentHeight < 26) { // Kelp has max natural height of 26
                Block above = topBlock.getRelative(0, 1, 0);
                if (above.getType() == Material.WATER) {
                    above.setType(currentHeight == 1 ? Material.KELP : Material.KELP_PLANT);
                    topBlock = above;
                    currentHeight++;
                    data.currentHeight = currentHeight;
                } else {
                    break;
                }
            }

            if (data.currentHeight != cropsConfig.getInt("crops." + key + ".height")) {
                cropsConfig.set("crops." + key + ".height", data.currentHeight);
                saveFile();
            }
        }
    }

    private void simulateStackingGrowth(Block baseBlock, CropData data, String key) {
        if (baseBlock.getType() != data.type) return;

        Biome biome = baseBlock.getBiome();
        long growInterval = getGrowIntervalSeconds(biome, data.type) * 1000L;
        int maxHeight = getMaxHeight(biome, data.type);

        if (growInterval <= 0) return;

        long elapsed = System.currentTimeMillis() - data.planted;
        int targetHeight = Math.min((int) (elapsed / growInterval) + 1, maxHeight);

        if (targetHeight > data.currentHeight) {
            Block topBlock = baseBlock;
            int currentHeight = 1;
            
            while (currentHeight < data.currentHeight) {
                Block above = topBlock.getRelative(0, 1, 0);
                if (above.getType() == data.type) {
                    topBlock = above;
                    currentHeight++;
                } else {
                    break;
                }
            }

            while (currentHeight < targetHeight) {
                Block above = topBlock.getRelative(0, 1, 0);
                if (above.getType() == Material.AIR) {
                    above.setType(data.type);
                    topBlock = above;
                    currentHeight++;
                    data.currentHeight = currentHeight;
                } else {
                    break;
                }
            }

            if (data.currentHeight != cropsConfig.getInt("crops." + key + ".height")) {
                cropsConfig.set("crops." + key + ".height", data.currentHeight);
                saveFile();
            }
        }
    }

    private void simulateAgeableGrowth(Block block, Ageable ageable, CropData data) {
        Biome biome = block.getBiome();
        long elapsed = System.currentTimeMillis() - data.planted;
        long growTime = getGrowTimeSeconds(biome, data.type) * 1000L;

        if (growTime <= 0) return;

        int maxAge = ageable.getMaximumAge();
        double progress = Math.min(1.0, (double) elapsed / growTime);
        int newAge = (int) Math.floor(progress * (maxAge + 1));

        if (newAge > ageable.getAge()) {
            ageable.setAge(Math.min(newAge, maxAge));
            block.setBlockData(ageable);
        }
    }

    private void simulateCocoaGrowth(Block block, org.bukkit.block.data.type.Cocoa cocoa, CropData data) {
        Biome biome = block.getBiome();
        long elapsed = System.currentTimeMillis() - data.planted;
        long growTime = getGrowTimeSeconds(biome, data.type) * 1000L;

        if (growTime <= 0) return;

        int maxAge = 2;
        double progress = Math.min(1.0, (double) elapsed / growTime);
        int newAge = (int) Math.floor(progress * (maxAge + 1));

        if (newAge > cocoa.getAge()) {
            cocoa.setAge(Math.min(newAge, maxAge));
            block.setBlockData(cocoa);
        }
    }

    private void simulateStemGrowth(Block stemBlock, Ageable ageable, CropData data) {
        Biome biome = stemBlock.getBiome();
        long elapsed = System.currentTimeMillis() - data.planted;
        long growTime = getGrowTimeSeconds(biome, data.type) * 1000L;

        if (growTime <= 0) return;

        int maxAge = ageable.getMaximumAge();
        double progress = Math.min(1.0, (double) elapsed / growTime);
        int newAge = (int) Math.floor(progress * (maxAge + 1));

        if (newAge > ageable.getAge()) {
            ageable.setAge(Math.min(newAge, maxAge));
            stemBlock.setBlockData(ageable);
        }

        if (ageable.getAge() >= maxAge) {
            long fruitInterval = getFruitIntervalSeconds(biome, data.type) * 1000L;
            if (fruitInterval <= 0) fruitInterval = 1000L;

            int existingFruits = countAdjacentFruits(stemBlock, data.type);

            String key = key(stemBlock);
            long lastFruitGrowth = cropsConfig.getLong("crops." + key + ".lastFruitGrowth", data.planted + growTime);

            long timeSinceLastFruit = System.currentTimeMillis() - lastFruitGrowth;
            boolean shouldGrowFruit = timeSinceLastFruit >= fruitInterval && existingFruits < 4;

            if (shouldGrowFruit) {
                if (growFruit(stemBlock, data.type)) {
                    cropsConfig.set("crops." + key + ".lastFruitGrowth", System.currentTimeMillis());
                    saveFile();
                }
            }
        }
    }

    private int countAdjacentFruits(Block stem, Material stemType) {
        Material fruitType = (stemType == Material.PUMPKIN_STEM) ? Material.PUMPKIN : Material.MELON;
        int count = 0;
        
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (Math.abs(dx) + Math.abs(dz) != 1) continue; 
                
                Block adjacent = stem.getRelative(dx, 0, dz);
                if (adjacent.getType() == fruitType) {
                    count++;
                }
            }
        }
        
        return count;
    }

    private boolean growFruit(Block stem, Material stemType) {
        Material fruitType = (stemType == Material.PUMPKIN_STEM) ? Material.PUMPKIN : Material.MELON;
        List<Block> validSpots = new ArrayList<>();
        
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (Math.abs(dx) + Math.abs(dz) != 1) continue;
                
                Block adjacent = stem.getRelative(dx, 0, dz);
                Block below = adjacent.getRelative(0, -1, 0);
                
                if (adjacent.getType() == Material.AIR && isSuitableGround(below.getType())) {
                    validSpots.add(adjacent);
                }
            }
        }
        
        if (!validSpots.isEmpty()) {
            Block target = validSpots.get(new Random().nextInt(validSpots.size()));
            target.setType(fruitType);
            return true;
        }
        
        return false;
    }

    private boolean isSuitableGround(Material material) {
        return switch (material) {
            case DIRT, GRASS_BLOCK, FARMLAND, PODZOL, COARSE_DIRT, 
                 ROOTED_DIRT, MYCELIUM, MOSS_BLOCK -> true;
            default -> false;
        };
    }

    private long getFruitIntervalSeconds(Biome biome, Material stemType) {
        String biomeName = biome.name();
        String configKey = stemType.name() + "-fruit-interval";
        
        if (biomesConfig.contains("biomes." + biomeName)) {
            String path = "biomes." + biomeName + "." + configKey;
            if (biomesConfig.contains(path)) {
                return biomesConfig.getLong(path);
            }
        }
        
        String group = getBiomeGroup(biomeName);
        if (group != null && biomesConfig.contains("groups." + group)) {
            String path = "groups." + group + "." + configKey;
            if (biomesConfig.contains(path)) {
                return biomesConfig.getLong(path);
            }
        }
        
        return -1;
    }

    private long getGrowTimeSeconds(Biome biome, Material type) {
        String biomeName = biome.name();
        
        if (biomesConfig.contains("biomes." + biomeName)) {
            String path = "biomes." + biomeName + "." + type.name();
            if (biomesConfig.contains(path)) {
                return biomesConfig.getLong(path);
            }
        }
        
        String group = getBiomeGroup(biomeName);
        if (group != null && biomesConfig.contains("groups." + group)) {
            String path = "groups." + group + "." + type.name();
            if (biomesConfig.contains(path)) {
                return biomesConfig.getLong(path);
            }
        }
        
        return -1;
    }

    private long getGrowIntervalSeconds(Biome biome, Material type) {
        String biomeName = biome.name();
        
        if (biomesConfig.contains("biomes." + biomeName)) {
            String path = "biomes." + biomeName + "." + type.name() + "-interval";
            if (biomesConfig.contains(path)) {
                return biomesConfig.getLong(path);
            }
        }
        
        String group = getBiomeGroup(biomeName);
        if (group != null && biomesConfig.contains("groups." + group)) {
            String path = "groups." + group + "." + type.name() + "-interval";
            if (biomesConfig.contains(path)) {
                return biomesConfig.getLong(path);
            }
        }
        
        return -1;
    }

    private int getMaxHeight(Biome biome, Material type) {
        String biomeName = biome.name();
        
        if (biomesConfig.contains("biomes." + biomeName)) {
            String path = "biomes." + biomeName + "." + type.name() + "-max-height";
            if (biomesConfig.contains(path)) {
                return biomesConfig.getInt(path);
            }
        }
        
        String group = getBiomeGroup(biomeName);
        if (group != null && biomesConfig.contains("groups." + group)) {
            String path = "groups." + group + "." + type.name() + "-max-height";
            if (biomesConfig.contains(path)) {
                return biomesConfig.getInt(path);
            }
        }
        
        return biomesConfig.getInt("default-max-height", 3);
    }

    private String getBiomeGroup(String biomeName) {
        for (Map.Entry<String, Set<String>> entry : biomeGroups.entrySet()) {
            if (entry.getValue().contains(biomeName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private Block findBaseBlock(Block block) {
        Material type = block.getType();
        Block current = block;
        
        while (current.getRelative(0, -1, 0).getType() == type) {
            current = current.getRelative(0, -1, 0);
        }
        
        return current;
    }

    // File persistence
    private void loadCrops() {
        if (cropsConfig.getConfigurationSection("crops") == null) return;

        for (String key : cropsConfig.getConfigurationSection("crops").getKeys(false)) {
            String path = "crops." + key;
            String typeName = cropsConfig.getString(path + ".type");
            long planted = cropsConfig.getLong(path + ".planted");
            int height = cropsConfig.getInt(path + ".height", 1);
            try {
                Material type = Material.valueOf(typeName);
                cropCache.put(key, new CropData(type, planted, height));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void saveCrops() {
        cropsConfig.set("crops", null);
        for (Map.Entry<String, CropData> entry : cropCache.entrySet()) {
            String path = "crops." + entry.getKey();
            CropData data = entry.getValue();
            cropsConfig.set(path + ".type", data.type.name());
            cropsConfig.set(path + ".planted", data.planted);
            if (isStackingCrop(data.type)) {
                cropsConfig.set(path + ".height", data.currentHeight);
            }
        }
        saveFile();
    }

    private void saveFile() {
        try {
            cropsConfig.save(cropsFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Helpers
    private void loadBiomeConfig() {
        biomesConfig = YamlConfiguration.loadConfiguration(biomesFile);
    }

    private boolean isCrop(Material mat) {
        return switch (mat) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, CACTUS, SUGAR_CANE, 
                 SWEET_BERRY_BUSH, COCOA, PUMPKIN_STEM, MELON_STEM, 
                 ATTACHED_PUMPKIN_STEM, ATTACHED_MELON_STEM,
                 OAK_SAPLING, SPRUCE_SAPLING, BIRCH_SAPLING, JUNGLE_SAPLING,
                 ACACIA_SAPLING, DARK_OAK_SAPLING, MANGROVE_PROPAGULE,
                 CHERRY_SAPLING, BAMBOO, KELP, KELP_PLANT -> true;
            default -> false;
        };
    }
    
    private boolean isStackingCrop(Material mat) {
        return mat == Material.CACTUS || mat == Material.SUGAR_CANE || 
               mat == Material.BAMBOO || mat == Material.KELP || mat == Material.KELP_PLANT;
    }

    // Add new method to check if material is a sapling
    private boolean isSapling(Material mat) {
        return switch (mat) {
            case OAK_SAPLING, SPRUCE_SAPLING, BIRCH_SAPLING, JUNGLE_SAPLING,
                 ACACIA_SAPLING, DARK_OAK_SAPLING, MANGROVE_PROPAGULE,
                 CHERRY_SAPLING -> true;
            default -> false;
        };
    }

    // Add new method to check if material is kelp
    private boolean isKelp(Material mat) {
        return mat == Material.KELP || mat == Material.KELP_PLANT;
    }

    // Add new method to check if material is bamboo
    private boolean isBamboo(Material mat) {
        return mat == Material.BAMBOO;
    }

    // Helper method to get TreeType from sapling Material
    private TreeType getTreeType(Material saplingType) {
        return switch (saplingType) {
            case OAK_SAPLING -> TreeType.TREE;
            case SPRUCE_SAPLING -> TreeType.REDWOOD;
            case BIRCH_SAPLING -> TreeType.BIRCH;
            case JUNGLE_SAPLING -> TreeType.JUNGLE;
            case ACACIA_SAPLING -> TreeType.ACACIA;
            case DARK_OAK_SAPLING -> TreeType.DARK_OAK;
            case MANGROVE_PROPAGULE -> TreeType.MANGROVE;
            case CHERRY_SAPLING -> TreeType.CHERRY;
            default -> null;
        };
    }

    private String key(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private static class CropData {
        public final Material type;
        public final long planted;
        public int currentHeight;

        public CropData(Material type, long planted, int currentHeight) {
            this.type = type;
            this.planted = planted;
            this.currentHeight = currentHeight;
        }
    }
}