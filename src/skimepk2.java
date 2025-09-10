import java.util.*;

public class skimepk2 {

    // ====== 参数区（按需修改）======
    static final long SEED = 2950649267509295309L; // 世界种子

    // 计算范围（区块坐标，含端点）
    static final int MIN_CHUNK_X = -100000;
    static final int MAX_CHUNK_X =  100000;
    static final int MIN_CHUNK_Z = -100000;
    static final int MAX_CHUNK_Z =  100000;

    // 窗口大小（8 表示 8×8）
    static final int WINDOW_SIZE = 8;

    // 输出数量
    static final int TOP_K = 50;

    // X 方向分片宽度（窗口起点带重叠处理）
    static final int BAND_COLS = 4096;   // 可调：越大内存占用越多，速度略快

    // 抽样步长（窗口起点步进；1=不抽样，全量扫描；设为8可做粗扫）
    static final int SKIP = 1;

    // 进度条
    static final boolean SHOW_PROGRESS = true;
    static final int PROGRESS_BAR_WIDTH = 40;

    // 原点（最少列表按距此点排序）
    static final int ORIGIN_X = 0, ORIGIN_Z = 0;
    // =================================

    static class Win {
        int startX, startZ, count;
        Win(int sx, int sz, int c){ startX=sx; startZ=sz; count=c; }
    }

    public static boolean isSlimeChunk(long worldSeed, int chunkX, int chunkZ) {
        Random rng = new Random(
                worldSeed +
                        (long)(chunkX * chunkX * 4987142) +
                        (long)(chunkX * 5947611) +
                        (long)(chunkZ * chunkZ) * 4392871L +
                        (long)(chunkZ * 389711) ^ 987234911L
        );
        return rng.nextInt(10) == 0;
    }

    // 顶部堆：保留“最好 TOP_K 个”，堆顶是“这 TOP_K 里最差的”
    static final Comparator<Win> TOP_HEAP_COMP = (a, b) -> {
        if (a.count != b.count) return a.count - b.count;     // 小的更“差”
        if (a.startZ != b.startZ) return b.startZ - a.startZ; // Z 大的更“差”
        return b.startX - a.startX;                            // X 大的更“差”
    };
    static boolean betterThanTopRoot(Win cand, Win root){
        if (cand.count != root.count) return cand.count > root.count;
        if (cand.startZ != root.startZ) return cand.startZ < root.startZ;
        return cand.startX < root.startX;
    }

    // 底部堆：保留“最差 TOP_K 个”，堆顶是“这 TOP_K 里最好的”（用于被替换）
    static final Comparator<Win> BOTTOM_HEAP_COMP = (a, b) -> {
        if (a.count != b.count) return b.count - a.count;     // 大的更“好”
        if (a.startZ != b.startZ) return b.startZ - a.startZ; // Z 大的更“好”
        return b.startX - a.startX;                            // X 大的更“好”
    };
    static boolean betterForBottomThanRoot(Win cand, Win root){
        if (cand.count != root.count) return cand.count < root.count;
        if (cand.startZ != root.startZ) return cand.startZ < root.startZ;
        return cand.startX < root.startX;
    }

    // 简易进度条
    static void drawProgress(String label, long current, long total) {
        if (!SHOW_PROGRESS) return;
        double pct = total == 0 ? 100.0 : (current * 100.0 / total);
        int filled = (int)Math.round(PROGRESS_BAR_WIDTH * pct / 100.0);
        if (filled > PROGRESS_BAR_WIDTH) filled = PROGRESS_BAR_WIDTH;
        String bar = repeat('#', filled) + repeat('.', PROGRESS_BAR_WIDTH - filled);
        System.out.printf("\r%s [%s] %5.1f%% (%d/%d)", label, bar, pct, current, total);
        System.out.flush();
        if (current >= total) System.out.println();
    }
    static String repeat(char ch, int count) {
        char[] arr = new char[Math.max(0, count)];
        Arrays.fill(arr, ch);
        return new String(arr);
    }

    // 计算平方距离（用于最少列表排序）
    static long dist2(int x, int z) {
        long dx = 1L * x - ORIGIN_X;
        long dz = 1L * z - ORIGIN_Z;
        return dx*dx + dz*dz;
    }

    public static void main(String[] args) {

        final int width  = MAX_CHUNK_X - MIN_CHUNK_X + 1;
        final int height = MAX_CHUNK_Z - MIN_CHUNK_Z + 1;

        // ===== 配置概要 =====
        long worldMinX = (long)MIN_CHUNK_X * 16L;
        long worldMaxX = (long)(MAX_CHUNK_X + 1) * 16L - 1L;
        long worldMinZ = (long)MIN_CHUNK_Z * 16L;
        long worldMaxZ = (long)(MAX_CHUNK_Z + 1) * 16L - 1L;
        System.out.println("种子: " + SEED);
        System.out.println("区域大小: " + WINDOW_SIZE + "x" + WINDOW_SIZE);
        System.out.println("计算范围: 区块X[" + MIN_CHUNK_X + " to " + MAX_CHUNK_X + "], Z[" + MIN_CHUNK_Z + " to " + MAX_CHUNK_Z + "]");
        System.out.println("世界坐标范围: X[" + worldMinX + " to " + worldMaxX + "], Z[" + worldMinZ + " to " + worldMaxZ + "]");
        System.out.println("每个区域大小: " + WINDOW_SIZE + "x" + WINDOW_SIZE + " 区块");
        long xStarts = Math.max(0, (width  - WINDOW_SIZE + 1 + (SKIP - 1)) / SKIP);
        long zStarts = Math.max(0, (height - WINDOW_SIZE + 1 + (SKIP - 1)) / SKIP);
        long totalWindows = xStarts * zStarts;
        System.out.println("窗口总数(考虑抽样SKIP=" + SKIP + "): " + totalWindows);
        System.out.println();
        // ====================

        if (width < WINDOW_SIZE || height < WINDOW_SIZE) {
            System.out.println("范围过小：无法放下 " + WINDOW_SIZE + "×" + WINDOW_SIZE + " 的窗口。");
            return;
        }

        // 在线筛选 top/bottom 50
        PriorityQueue<Win> topHeap = new PriorityQueue<>(TOP_K, TOP_HEAP_COMP);
        PriorityQueue<Win> bottomHeap = new PriorityQueue<>(TOP_K, BOTTOM_HEAP_COMP);

        // 计算 X 方向分片的起点（窗口起点坐标）
        final int xStartGlobalMin = MIN_CHUNK_X;
        final int xStartGlobalMax = MAX_CHUNK_X - WINDOW_SIZE + 1;

        // 分片步长（带重叠，避免跨片窗口缺失）
        final int bandStepStarts = Math.max(1, BAND_COLS - (WINDOW_SIZE - 1));

        long doneWindows = 0;

        for (int xStartBand = xStartGlobalMin; xStartBand <= xStartGlobalMax; xStartBand += bandStepStarts) {

            // 本片窗口起点范围
            int xStartMin = xStartBand;
            int xStartMax = Math.min(xStartBand + BAND_COLS - 1, xStartGlobalMax);

            // 本片需要的列范围（窗口宽度考虑）
            int colXMin = xStartMin;
            int colXMax = xStartMax + WINDOW_SIZE - 1;
            int nCols = colXMax - colXMin + 1;

            // 循环缓冲：最近 WINDOW_SIZE 行（0/1）
            byte[][] ring = new byte[WINDOW_SIZE][nCols];
            int[] colSums = new int[nCols]; // 每列纵向窗口高度内的和

            // 预填充前 WINDOW_SIZE 行
            int ringHead = 0; // 指向最旧行
            for (int r = 0; r < WINDOW_SIZE; r++) {
                int z = MIN_CHUNK_Z + r;
                // 生成一整行的 0/1
                for (int ci = 0; ci < nCols; ci++) {
                    int cx = colXMin + ci;
                    boolean slime = isSlimeChunk(SEED, cx, z);
                    ring[r][ci] = slime ? (byte)1 : (byte)0;
                    colSums[ci] += ring[r][ci];
                }
            }

            // Z 方向窗口起点范围
            int zStartMin = MIN_CHUNK_Z;
            int zStartMax = MAX_CHUNK_Z - WINDOW_SIZE + 1;

            // 本片每一行的窗口个数（考虑 SKIP）
            int xStartsThisBand = ((xStartMax - xStartMin) / SKIP) + 1;

            // 遍历所有 Z 起点（考虑 SKIP 抽样）
            for (int zStart = zStartMin; zStart <= zStartMax; zStart += SKIP) {

                // 计算这一行第一个窗口的和（横向）
                int baseIdx = 0; // 对应 xStart==xStartMin 的起点列索引
                int sum = 0;
                for (int k = 0; k < WINDOW_SIZE; k++) sum += colSums[baseIdx + k];

                // 遍历 X 起点（考虑 SKIP）
                int xStart = xStartMin;
                int idx = baseIdx;
                for (; xStart <= xStartMax; xStart += SKIP, idx += SKIP) {
                    int count = sum;
                    // 记录窗口
                    Win w = new Win(xStart, zStart, count);

                    // 维护 top 50
                    if (topHeap.size() < TOP_K) topHeap.add(w);
                    else if (betterThanTopRoot(w, topHeap.peek())) { topHeap.poll(); topHeap.add(w); }

                    // 维护 bottom 50
                    if (bottomHeap.size() < TOP_K) bottomHeap.add(w);
                    else if (betterForBottomThanRoot(w, bottomHeap.peek())) { bottomHeap.poll(); bottomHeap.add(w); }

                    // 横向滑动到下一个
                    if (xStart + SKIP <= xStartMax) {
                        // 抽样步长为 SKIP：一次跨过 SKIP 个起点
                        for (int step = 0; step < SKIP; step++) {
                            int leaveIdx = idx + step;
                            int enterIdx = leaveIdx + WINDOW_SIZE;
                            if (leaveIdx + 1 > idx + SKIP - 1) break; // 防护
                            sum += colSums[enterIdx] - colSums[leaveIdx];
                        }
                    }
                }

                doneWindows += xStartsThisBand;
                drawProgress("3/3 扫描窗口 (X片起点=" + xStartBand + ")", doneWindows, totalWindows);

                // 向下滑动一行（更新循环缓冲和列和），准备下一个 zStart
                if (zStart + SKIP <= zStartMax) {
                    // 需要追加的行数 = SKIP
                    for (int add = 0; add < SKIP; add++) {
                        int zLeaveIdx = ringHead; // 将要被替换的最旧行
                        int zEnter = zStart + WINDOW_SIZE + add; // 新进入的绝对 Z 行

                        // 逐列更新
                        for (int ci = 0; ci < nCols; ci++) {
                            int cx = colXMin + ci;
                            int leave = ring[zLeaveIdx][ci];
                            int enter = isSlimeChunk(SEED, cx, zEnter) ? 1 : 0;
                            colSums[ci] += enter - leave;
                            ring[zLeaveIdx][ci] = (byte)enter;
                        }
                        ringHead = (ringHead + 1) % WINDOW_SIZE;
                    }
                }
            }
        }

        // 4) 排序并输出
        List<Win> topList = new ArrayList<>(topHeap);
        topList.sort((a, b) -> {
            if (b.count != a.count) return b.count - a.count;  // 多到少
            if (a.startZ != b.startZ) return a.startZ - b.startZ;
            return a.startX - b.startX;
        });

        List<Win> bottomList = new ArrayList<>(bottomHeap);
        // 最少的：按距(ORIGIN_X,ORIGIN_Z)从近到远；同距时 count 升序、Z 升序、X 升序
        bottomList.sort((a, b) -> {
            long d2a = dist2(a.startX, a.startZ);
            long d2b = dist2(b.startX, b.startZ);
            if (d2a != d2b) return Long.compare(d2a, d2b);
            if (a.count != b.count) return Integer.compare(a.count, b.count);
            if (a.startZ != b.startZ) return Integer.compare(a.startZ, b.startZ);
            return Integer.compare(a.startX, b.startX);
        });

        int totalInWin = WINDOW_SIZE * WINDOW_SIZE;

        // === 最多的 TOP_K 条 ===
        System.out.println("\n=== 最多的 " + Math.min(TOP_K, topList.size()) + " 条 ===");
        for (Win w : topList) {
            long wx = (long)w.startX * 16L;
            long wz = (long)w.startZ * 16L;
            double pct = w.count * 100.0 / totalInWin;
            System.out.printf("%d|x,z=%d,%d|X,Z=%d,%d|%dx%d|%.1f%%%n",
                    w.count, wx, wz, w.startX, w.startZ, WINDOW_SIZE, WINDOW_SIZE, pct);
        }

        // === 最少的 TOP_K 条（按距原点近→远）===
        System.out.println("\n=== 最少的 " + Math.min(TOP_K, bottomList.size()) + " 条（按距原点近→远） ===");
        for (Win w : bottomList) {
            long wx = (long)w.startX * 16L;
            long wz = (long)w.startZ * 16L;
            double pct = w.count * 100.0 / totalInWin;
            System.out.printf("%d|x,z=%d,%d|X,Z=%d,%d|%dx%d|%.1f%%%n",
                    w.count, wx, wz, w.startX, w.startZ, WINDOW_SIZE, WINDOW_SIZE, pct);
        }
    }
}
