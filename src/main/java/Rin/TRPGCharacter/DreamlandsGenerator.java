package Rin.TRPGCharacter;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.Random;

/**
 * 自然豊かなクトゥルフ神話風ドリームランド地形。
 *
 * 約67%を幻想自然地域、約33%を従来の神話荒野として生成する。
 * Paper 1.20.1互換のためChunkData#setBiomeは使用しない。
 */
public class DreamlandsGenerator extends ChunkGenerator {

    private static final int SEA_LEVEL = 48;
    private static final int MIN_SURFACE = 28;
    private static final int MAX_SURFACE = 122;

    @Override
    public void generateNoise(WorldInfo worldInfo,
                              Random random,
                              int chunkX,
                              int chunkZ,
                              ChunkData chunkData) {
        long seed = worldInfo.getSeed();

        SimplexOctaveGenerator continental = new SimplexOctaveGenerator(seed ^ 0x4D5954484F534C41L, 6);
        continental.setScale(0.0065);

        SimplexOctaveGenerator ridges = new SimplexOctaveGenerator(seed ^ 0x524C5945485F4E47L, 4);
        ridges.setScale(0.018);

        SimplexOctaveGenerator detail = new SimplexOctaveGenerator(seed ^ 0x445245414D5F4E31L, 3);
        detail.setScale(0.045);

        SimplexOctaveGenerator wetness = new SimplexOctaveGenerator(seed ^ 0x5745545F44524541L, 3);
        wetness.setScale(0.021);

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = chunkX * 16 + lx;
                int wz = chunkZ * 16 + lz;

                DreamlandsRegion region = DreamlandsRegion.at(seed, wx, wz);

                double c = continental.noise(wx, wz, 0.55, 0.5, true);
                double r = Math.abs(ridges.noise(wx, wz, 0.55, 0.5, true));
                double d = detail.noise(wx, wz, 0.5, 0.5, true);
                double w = wetness.noise(wx, wz, 0.5, 0.5, true);

                double heightValue = baseHeight(region, c, r, d, w);

                // 原点周辺は初期スポーン確保のため月光草原風の穏やかな高台。
                double dist = Math.sqrt((double) wx * wx + (double) wz * wz);
                if (dist < 48.0) {
                    double blend = Math.max(0.0, 1.0 - dist / 48.0);
                    heightValue = heightValue * (1.0 - blend) + 72.0 * blend;
                    region = DreamlandsRegion.MOON_MEADOW;
                }

                int surface = clamp((int) Math.round(heightValue), MIN_SURFACE, MAX_SURFACE);

                chunkData.setBlock(lx, worldInfo.getMinHeight(), lz, Material.BEDROCK);
                for (int y = worldInfo.getMinHeight() + 1; y <= surface; y++) {
                    int depth = surface - y;
                    Material material;

                    if (depth == 0) {
                        material = pickSurface(region, c, r, d, w, wx, wz);
                    } else if (depth <= 3) {
                        material = pickSubsurface(region, c, r, wx, wz);
                    } else if (y < 18) {
                        material = Material.DEEPSLATE;
                    } else {
                        material = pickBody(region, wx, y, wz, seed);
                    }
                    chunkData.setBlock(lx, y, lz, material);
                }

                int localSea = region == DreamlandsRegion.MOONLAKE ? 54
                        : region == DreamlandsRegion.OTHERWORLD_SWAMP ? 50
                        : SEA_LEVEL;

                if (surface < localSea) {
                    for (int y = surface + 1; y <= localSea; y++) {
                        chunkData.setBlock(lx, y, lz, Material.WATER);
                    }
                }

                decorateSurface(worldInfo, chunkData, region, seed, lx, lz, wx, wz, surface, localSea, dist);

                // 荒野と渓谷は裂け目を多めに残す。
                double fissure = Math.abs(detail.noise(wx + 4312, wz - 9771, 0.5, 0.5, true));
                double fissureLimit = region == DreamlandsRegion.MOSS_RAVINE ? 0.055
                        : region == DreamlandsRegion.BARRENS ? 0.035 : 0.018;

                if (fissure < fissureLimit && dist > 52.0 && surface > localSea + 8) {
                    int bottom = Math.max(worldInfo.getMinHeight() + 5, surface - 28);
                    for (int y = bottom; y <= surface + 2; y++) {
                        chunkData.setBlock(lx, y, lz, Material.AIR);
                    }

                    // 苔の渓谷は裂け目の壁に発光物を混ぜる。
                    if (region == DreamlandsRegion.MOSS_RAVINE && surface - 8 > bottom) {
                        chunkData.setBlock(lx, surface - 8, lz,
                                ((hash(wx, surface, wz, seed) & 1L) == 0L)
                                        ? Material.GLOWSTONE : Material.SHROOMLIGHT);
                    }
                }
            }
        }
    }

    private double baseHeight(DreamlandsRegion region,
                              double c, double r, double d, double w) {
        return switch (region) {
            case MOON_MEADOW -> 66.0 + c * 12.0 + d * 4.0;
            case ENCHANTED_FOREST -> 67.0 + c * 17.0 + r * 10.0 + d * 5.0;
            case MOSS_RAVINE -> 70.0 + c * 21.0 + Math.pow(r, 1.8) * 18.0 + d * 6.0;
            case OTHERWORLD_SWAMP -> 51.0 + c * 6.0 + w * 3.5;
            case FUNGAL_GROVE -> 62.0 + c * 13.0 + d * 4.0;
            case MOONLAKE -> 43.0 + c * 7.0 + d * 2.0;
            case GLOW_CAVES -> 64.0 + c * 15.0 + r * 8.0 + d * 4.0;
            case BARRENS -> 58.0 + c * 31.0 + Math.pow(r, 2.1) * 36.0 + d * 7.0;
        };
    }

    private Material pickSurface(DreamlandsRegion region,
                                 double c, double r, double d, double w,
                                 int x, int z) {
        long h = hash(x, 0, z, 0xD34DCAFE1234L);
        return switch (region) {
            case MOON_MEADOW -> (h & 31L) == 0L ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
            case ENCHANTED_FOREST -> (h & 7L) == 0L ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
            case MOSS_RAVINE -> (h & 3L) == 0L ? Material.ROOTED_DIRT : Material.MOSS_BLOCK;
            case OTHERWORLD_SWAMP -> w > 0.15 ? Material.MUD : ((h & 3L) == 0L ? Material.MOSS_BLOCK : Material.MUD);
            case FUNGAL_GROVE -> (h & 3L) == 0L ? Material.MYCELIUM : Material.MOSS_BLOCK;
            case MOONLAKE -> Material.CLAY;
            case GLOW_CAVES -> (h & 7L) == 0L ? Material.SCULK : Material.MOSS_BLOCK;
            case BARRENS -> {
                if (c < -0.38) yield Material.SOUL_SAND;
                if (r > 0.62) yield Material.BLACKSTONE;
                if (d > 0.45) yield Material.SCULK;
                if ((h & 7L) == 0L) yield Material.COARSE_DIRT;
                if ((h & 15L) == 1L) yield Material.MOSS_BLOCK;
                yield Material.DEEPSLATE;
            }
        };
    }

    private Material pickSubsurface(DreamlandsRegion region,
                                    double c, double r, int x, int z) {
        long h = hash(x, 1, z, 0x55AA33CC77L);
        return switch (region) {
            case MOON_MEADOW, ENCHANTED_FOREST -> (h & 3L) == 0L ? Material.ROOTED_DIRT : Material.DIRT;
            case MOSS_RAVINE -> Material.ROOTED_DIRT;
            case OTHERWORLD_SWAMP -> (h & 1L) == 0L ? Material.MUD : Material.CLAY;
            case FUNGAL_GROVE -> (h & 3L) == 0L ? Material.DIRT : Material.ROOTED_DIRT;
            case MOONLAKE -> Material.CLAY;
            case GLOW_CAVES -> (h & 3L) == 0L ? Material.TUFF : Material.DEEPSLATE;
            case BARRENS -> {
                if (c < -0.38) yield Material.SOUL_SOIL;
                if (r > 0.62) yield Material.BASALT;
                yield (h & 3L) == 0L ? Material.TUFF : Material.DEEPSLATE;
            }
        };
    }

    private Material pickBody(DreamlandsRegion region, int x, int y, int z, long seed) {
        long h = hash(x, y, z, seed);

        if (region == DreamlandsRegion.GLOW_CAVES) {
            if ((h & 0x1FFL) == 3L) return Material.AMETHYST_BLOCK;
            if ((h & 0x3FFL) == 17L) return Material.SHROOMLIGHT;
            if ((h & 0x7FL) == 7L) return Material.CALCITE;
        }

        if (region == DreamlandsRegion.MOSS_RAVINE && (h & 0x1FFL) == 11L) {
            return Material.MOSS_BLOCK;
        }

        return (h & 15L) == 0L ? Material.TUFF : Material.DEEPSLATE;
    }

    private void decorateSurface(WorldInfo worldInfo,
                                 ChunkData chunkData,
                                 DreamlandsRegion region,
                                 long seed,
                                 int lx, int lz, int wx, int wz,
                                 int surface, int localSea, double dist) {
        if (surface + 1 >= worldInfo.getMaxHeight() - 2) return;
        long h = hash(wx, 0, wz, seed);

        // 水面装飾
        if (surface < localSea) {
            if ((region == DreamlandsRegion.OTHERWORLD_SWAMP || region == DreamlandsRegion.MOONLAKE)
                    && localSea + 1 < worldInfo.getMaxHeight()
                    && (h & 0x3FL) == 9L) {
                chunkData.setBlock(lx, localSea + 1, lz, Material.LILY_PAD);
            }
            return;
        }

        switch (region) {
            case MOON_MEADOW -> {
                if ((h & 0x1FL) == 3L) {
                    chunkData.setBlock(lx, surface + 1, lz,
                            ((h >>> 8) & 1L) == 0L ? Material.ALLIUM : Material.CORNFLOWER);
                } else if ((h & 0x7FL) == 17L) {
                    chunkData.setBlock(lx, surface + 1, lz, Material.AZALEA);
                } else if ((h & 0x1FFL) == 41L) {
                    chunkData.setBlock(lx, surface, lz, Material.SHROOMLIGHT);
                }
            }
            case ENCHANTED_FOREST -> {
                if ((h & 0xFFL) == 23L && lx >= 3 && lx <= 12 && lz >= 3 && lz <= 12) {
                    placeWarpedTree(worldInfo, chunkData, lx, lz, surface, h);
                } else if ((h & 0x3FL) == 7L) {
                    chunkData.setBlock(lx, surface + 1, lz, Material.WARPED_FUNGUS);
                } else if ((h & 0x7FL) == 35L) {
                    chunkData.setBlock(lx, surface + 1, lz, Material.AZALEA);
                }
            }
            case MOSS_RAVINE -> {
                if ((h & 0x3FL) == 5L) {
                    chunkData.setBlock(lx, surface + 1, lz, Material.FLOWERING_AZALEA);
                } else if ((h & 0xFFL) == 43L) {
                    chunkData.setBlock(lx, surface, lz, Material.SHROOMLIGHT);
                }
            }
            case OTHERWORLD_SWAMP -> {
                if ((h & 0x3FL) == 13L) {
                    chunkData.setBlock(lx, surface + 1, lz, Material.MANGROVE_PROPAGULE);
                } else if ((h & 0xFFL) == 73L) {
                    chunkData.setBlock(lx, surface + 1, lz, Material.BLUE_ORCHID);
                }
            }
            case FUNGAL_GROVE -> {
                if ((h & 0x7FL) == 19L && lx >= 2 && lx <= 13 && lz >= 2 && lz <= 13) {
                    placeGiantFungus(worldInfo, chunkData, lx, lz, surface, h);
                } else if ((h & 0x1FL) == 11L) {
                    chunkData.setBlock(lx, surface + 1, lz,
                            ((h >>> 9) & 1L) == 0L ? Material.RED_MUSHROOM : Material.BROWN_MUSHROOM);
                }
            }
            case GLOW_CAVES -> {
                if ((h & 0x3FL) == 21L) {
                    chunkData.setBlock(lx, surface + 1, lz, Material.AMETHYST_CLUSTER);
                } else if ((h & 0xFFL) == 91L) {
                    chunkData.setBlock(lx, surface, lz, Material.SHROOMLIGHT);
                }
            }
            case BARRENS -> {
                if ((h & 0x3FFL) == 17L && dist > 28.0) {
                    int height = 7 + (int) ((h >>> 12) & 15L);
                    for (int y = 1; y <= height && surface + y < worldInfo.getMaxHeight() - 1; y++) {
                        chunkData.setBlock(lx, surface + y, lz,
                                y % 5 == 0 ? Material.CRYING_OBSIDIAN : Material.OBSIDIAN);
                    }
                } else if ((h & 0x7FL) == 5L) {
                    chunkData.setBlock(lx, surface + 1, lz, Material.SCULK_VEIN);
                } else if ((h & 0x1FFL) == 31L) {
                    chunkData.setBlock(lx, surface + 1, lz, Material.WARPED_FUNGUS);
                } else if ((h & 0x3FFL) == 63L) {
                    chunkData.setBlock(lx, surface + 1, lz, Material.RED_MUSHROOM);
                }
            }
            case MOONLAKE -> {
                // 湖は水面の静けさを優先。
            }
        }
    }

    private void placeWarpedTree(WorldInfo worldInfo, ChunkData data,
                                 int x, int z, int surface, long h) {
        int height = 5 + (int) ((h >>> 14) & 3L);
        if (surface + height + 2 >= worldInfo.getMaxHeight()) return;

        for (int y = 1; y <= height; y++) {
            data.setBlock(x, surface + y, z, Material.WARPED_STEM);
        }

        int top = surface + height;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 3) continue;
                data.setBlock(x + dx, top, z + dz, Material.WARPED_WART_BLOCK);
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                    data.setBlock(x + dx, top + 1, z + dz, Material.WARPED_WART_BLOCK);
                }
            }
        }
        data.setBlock(x, top, z, Material.SHROOMLIGHT);
    }

    private void placeGiantFungus(WorldInfo worldInfo, ChunkData data,
                                  int x, int z, int surface, long h) {
        int height = 4 + (int) ((h >>> 12) & 3L);
        if (surface + height + 2 >= worldInfo.getMaxHeight()) return;

        Material cap = ((h >>> 20) & 1L) == 0L
                ? Material.RED_MUSHROOM_BLOCK : Material.BROWN_MUSHROOM_BLOCK;

        for (int y = 1; y <= height; y++) {
            data.setBlock(x, surface + y, z, Material.MUSHROOM_STEM);
        }

        int top = surface + height;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz <= 5) {
                    data.setBlock(x + dx, top, z + dz, cap);
                }
            }
        }
        data.setBlock(x, top - 1, z, Material.SHROOMLIGHT);
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateBedrock() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return true;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return true;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private long hash(int x, int y, int z, long seed) {
        long h = seed;
        h ^= (long) x * 0x9E3779B97F4A7C15L;
        h ^= (long) y * 0xC2B2AE3D27D4EB4FL;
        h ^= (long) z * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
