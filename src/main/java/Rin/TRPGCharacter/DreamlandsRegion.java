package Rin.TRPGCharacter;

/**
 * ドリームランドの大規模自然地域。
 * 512ブロック前後のVoronoiセルを使い、ワールドseedから決定するため
 * 再起動しても地域配置が変化しない。
 */
public enum DreamlandsRegion {
    MOON_MEADOW("月光草原"),
    ENCHANTED_FOREST("幻想森林"),
    MOSS_RAVINE("苔の渓谷"),
    OTHERWORLD_SWAMP("異界湿地"),
    FUNGAL_GROVE("巨大菌類林"),
    MOONLAKE("月影の湖"),
    GLOW_CAVES("発光洞窟"),
    BARRENS("神話荒野");

    private final String displayName;

    DreamlandsRegion(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static DreamlandsRegion at(long seed, int x, int z) {
        final int cellSize = 512;
        int cx = Math.floorDiv(x, cellSize);
        int cz = Math.floorDiv(z, cellSize);

        double best = Double.MAX_VALUE;
        int bestCx = cx;
        int bestCz = cz;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int tcx = cx + dx;
                int tcz = cz + dz;
                long h = hash(seed, tcx, tcz);

                double jitterX = (((h >>> 8) & 0xFFFFL) / 65535.0 - 0.5) * cellSize * 0.68;
                double jitterZ = (((h >>> 24) & 0xFFFFL) / 65535.0 - 0.5) * cellSize * 0.68;

                double centerX = (tcx + 0.5) * cellSize + jitterX;
                double centerZ = (tcz + 0.5) * cellSize + jitterZ;

                double ddx = x - centerX;
                double ddz = z - centerZ;
                double distance = ddx * ddx + ddz * ddz;
                if (distance < best) {
                    best = distance;
                    bestCx = tcx;
                    bestCz = tcz;
                }
            }
        }

        long chosen = hash(seed ^ 0x445245414D524547L, bestCx, bestCz);
        int roll = (int) Math.floorMod(chosen, 100L);

        // 自然系67%、神話荒野33%。
        if (roll < 10) return MOON_MEADOW;
        if (roll < 21) return ENCHANTED_FOREST;
        if (roll < 31) return MOSS_RAVINE;
        if (roll < 40) return OTHERWORLD_SWAMP;
        if (roll < 49) return FUNGAL_GROVE;
        if (roll < 58) return MOONLAKE;
        if (roll < 67) return GLOW_CAVES;
        return BARRENS;
    }

    private static long hash(long seed, int x, int z) {
        long h = seed;
        h ^= (long) x * 0x9E3779B97F4A7C15L;
        h ^= (long) z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
