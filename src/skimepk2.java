import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class skimepk2 {

    // ====== 参数区（按需修改）======
    static final long SEED = 2950649267509295309L; // 世界种子

    // 计算范围（区块坐标，含端点）
    static final int MIN_CHUNK_X = -5000;
    static final int MAX_CHUNK_X =  5000;
    static final int MIN_CHUNK_Z = -5000;
    static final int MAX_CHUNK_Z =  5000;

    // 窗口大小（8 表示 8×8）
    static final int WINDOW_SIZE = 12;

    // 输出数量
    static final int TOP_K = 50;

    // X 方向“窗口起点”的分片宽度（带窗口重叠，避免跨片窗口缺失）
    static final int BAND_COLS = 4096;   // 可调：越大速度越快但占用稍增

    // 抽样步长：窗口起点步进（1=全量；8/16 用于粗扫加速）
    static final int SKIP = 1;

    // 线程数（默认 = CPU 核心数）
    static final int THREADS = Math.max(1, Runtime.getRuntime().availableProcessors());

    // 进度条
    static final boolean SHOW_PROGRESS = true;
    static final int PROGRESS_BAR_WIDTH = 40;
    static final int PROGRESS_REFRESH_MS = 200;

    // “距离原点”所用的原点（可改为你的基地区块坐标）
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

    // ======== 在线筛选的堆规则（与最终偏好一致）========
    // 顶部堆：保留“最好 TOP_K 个”，堆顶是“这 TOP_K 里最差的”
    static final Comparator<Win> TOP_HEAP_COMP = (a, b) -> {
        if (a.count != b.count) return a.count - b.count;              // 小count更差 → 靠堆顶
        long d2a = dist2(a.startX, a.startZ), d2b = dist2(b.startX, b.startZ);
        if (d2a != d2b) return Long.compare(d2b, d2a);                 // 远者更差 → 靠堆顶
        if (a.startZ != b.startZ) return b.startZ - a.startZ;          // 大Z更差
        return b.startX - a.startX;                                     // 大X更差
    };
    static boolean betterThanTopRoot(Win cand, Win root){
        if (cand.count != root.count) return cand.count > root.count;   // 大count更好
        long d2c = dist2(cand.startX, cand.startZ), d2r = dist2(root.startX, root.startZ);
        if (d2c != d2r) return d2c < d2r;                               // 近者更好
        if (cand.startZ != root.startZ) return cand.startZ < root.startZ; // 小Z更好
        return cand.startX < root.startX;                                // 小X更好
    }

    // 底部堆：保留“最差 TOP_K 个”，堆顶是“这 TOP_K 里最好的”（方便被更差的替换）
    static final Comparator<Win> BOTTOM_HEAP_COMP = (a, b) -> {
        if (a.count != b.count) return b.count - a.count;               // 大count更“好” → 靠堆顶
        long d2a = dist2(a.startX, a.startZ), d2b = dist2(b.startX, b.startZ);
        if (d2a != d2b) return Long.compare(d2a, d2b);                  // 近者更“好”
        if (a.startZ != b.startZ) return a.startZ - b.startZ;           // 小Z更“好”
        return a.startX - b.startX;                                     // 小X更“好”
    };
    static boolean betterForBottomThanRoot(Win cand, Win root){
        if (cand.count != root.count) return cand.count < root.count;   // 小count更差
        long d2c = dist2(cand.startX, cand.startZ), d2r = dist2(root.startX, root.startZ);
        if (d2c != d2r) return d2c < d2r;                               // 同count：更近更差（我们要保留近的）
        if (cand.startZ != root.startZ) return cand.startZ < root.startZ; // 小Z更差
        return cand.startX < root.startX;                                // 小X更差
    }
    // ====================================================

    static long dist2(int x, int z) {
        long dx = 1L * x - ORIGIN_X;
        long dz = 1L * z - ORIGIN_Z;
        return dx*dx + dz*dz;
    }

    // 简易进度条
    static void drawProgress(String label, long current, long total) {
        if (!SHOW_PROGRESS) return;
        double pct = total == 0 ? 100.0 : (current * 100.0 / total);
        int filled = (int)Math.round(PROGRESS_BAR_WIDTH * pct / 100.0);
        if (filled > PROGRESS_BAR_WIDTH) filled = PROGRESS_BAR_WIDTH;
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<filled;i++) sb.append('#');
        for (int i=filled;i<PROGRESS_BAR_WIDTH;i++) sb.append('.');
        System.out.printf("\r%s [%s] %5.1f%% (%d/%d)", label, sb, pct, current, total);
        System.out.flush();
        if (current >= total) System.out.println();
    }

    static class TaskResult {
        PriorityQueue<Win> top = new PriorityQueue<>(TOP_K, TOP_HEAP_COMP);
        PriorityQueue<Win> bottom = new PriorityQueue<>(TOP_K, BOTTOM_HEAP_COMP);
    }

    public static void main(String[] args) throws Exception {

        final int width  = MAX_CHUNK_X - MIN_CHUNK_X + 1;
        final int height = MAX_CHUNK_Z - MIN_CHUNK_Z + 1;

        // ===== 开始前概要 =====
        long worldMinX = (long)MIN_CHUNK_X * 16L;
        long worldMaxX = (long)(MAX_CHUNK_X + 1) * 16L - 1L;
        long worldMinZ = (long)MIN_CHUNK_Z * 16L;
        long worldMaxZ = (long)(MAX_CHUNK_Z + 1) * 16L - 1L;

        long xStarts = Math.max(0L, (width  - WINDOW_SIZE + 1 + (SKIP - 1)) / SKIP);
        long zStarts = Math.max(0L, (height - WINDOW_SIZE + 1 + (SKIP - 1)) / SKIP);
        final long totalWindows = xStarts * zStarts;

        System.out.println("种子: " + SEED);
        System.out.println("区域大小: " + WINDOW_SIZE + "x" + WINDOW_SIZE);
        System.out.println("计算范围: 区块X[" + MIN_CHUNK_X + " to " + MAX_CHUNK_X + "], Z[" + MIN_CHUNK_Z + " to " + MAX_CHUNK_Z + "]");
        System.out.println("世界坐标范围: X[" + worldMinX + " to " + worldMaxX + "], Z[" + worldMinZ + " to " + worldMaxZ + "]");
        System.out.println("每个区域大小: " + WINDOW_SIZE + "x" + WINDOW_SIZE + " 区块");
        System.out.println("窗口总数(考虑抽样 SKIP=" + SKIP + "): " + totalWindows);
        System.out.println();

        if (width < WINDOW_SIZE || height < WINDOW_SIZE) {
            System.out.println("范围过小：无法放下 " + WINDOW_SIZE + "×" + WINDOW_SIZE + " 的窗口。");
            return;
        }

        // 计算 X 方向“窗口起点”的全局范围
        final int xStartGlobalMin = MIN_CHUNK_X;
        final int xStartGlobalMax = MAX_CHUNK_X - WINDOW_SIZE + 1;

        // 分片步长（窗口起点），片间重叠 WINDOW_SIZE-1
        final int bandStepStarts = Math.max(1, BAND_COLS - (WINDOW_SIZE - 1));

        // 构造所有分片任务
        List<int[]> bands = new ArrayList<>();
        for (int xStartBand = xStartGlobalMin; xStartBand <= xStartGlobalMax; xStartBand += bandStepStarts) {
            int xStartMin = xStartBand;
            int xStartMax = Math.min(xStartBand + BAND_COLS - 1, xStartGlobalMax);
            bands.add(new int[]{xStartMin, xStartMax});
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        AtomicLong done = new AtomicLong(0);
        AtomicBoolean finished = new AtomicBoolean(false);

        // 进度监视器
        Thread monitor = new Thread(() -> {
            while (!finished.get()) {
                drawProgress("扫描窗口(多线程)", done.get(), totalWindows);
                try { Thread.sleep(PROGRESS_REFRESH_MS); } catch (InterruptedException ignored) {}
            }
            drawProgress("扫描窗口(多线程)", done.get(), totalWindows);
        });
        monitor.start();

        // 提交任务
        List<Future<TaskResult>> futures = new ArrayList<>();
        for (int[] band : bands) {
            final int xStartMin = band[0];
            final int xStartMax = band[1];

            futures.add(pool.submit(() -> {
                TaskResult tr = new TaskResult();

                // 本片需要的列范围（窗口宽度考虑）
                int colXMin = xStartMin;
                int colXMax = xStartMax + WINDOW_SIZE - 1;
                int nCols = colXMax - colXMin + 1;

                // 循环缓冲：最近 WINDOW_SIZE 行（0/1）
                byte[][] ring = new byte[WINDOW_SIZE][nCols];
                int[] colSums = new int[nCols]; // 每列纵向窗口高度内的和

                // 预填充前 WINDOW_SIZE 行
                int ringHead = 0;
                for (int r = 0; r < WINDOW_SIZE; r++) {
                    int z = MIN_CHUNK_Z + r;
                    for (int ci = 0; ci < nCols; ci++) {
                        int cx = colXMin + ci;
                        ring[r][ci] = isSlimeChunk(SEED, cx, z) ? (byte)1 : (byte)0;
                        colSums[ci] += ring[r][ci];
                    }
                }

                // Z 方向窗口起点范围
                int zStartMin = MIN_CHUNK_Z;
                int zStartMaxLocal = MAX_CHUNK_Z - WINDOW_SIZE + 1;

                // 本片每一行的窗口个数（考虑 SKIP）
                int xStartsThisBand = ((xStartMax - xStartMin) / SKIP) + 1;

                // 遍历所有 Z 起点（考虑 SKIP）
                for (int zStart = zStartMin; zStart <= zStartMaxLocal; zStart += SKIP) {

                    // 第一个窗口的和（起点为 xStartMin）
                    int baseIdx = 0;
                    int sum = 0;
                    for (int k = 0; k < WINDOW_SIZE; k++) sum += colSums[baseIdx + k];

                    // 遍历 X 起点（考虑 SKIP）
                    int idx = baseIdx;
                    for (int xStart = xStartMin; xStart <= xStartMax; xStart += SKIP, idx += SKIP) {
                        int count = sum;
                        Win w = new Win(xStart, zStart, count);

                        // 维护 top
                        if (tr.top.size() < TOP_K) tr.top.add(w);
                        else if (betterThanTopRoot(w, tr.top.peek())) { tr.top.poll(); tr.top.add(w); }

                        // 维护 bottom
                        if (tr.bottom.size() < TOP_K) tr.bottom.add(w);
                        else if (betterForBottomThanRoot(w, tr.bottom.peek())) { tr.bottom.poll(); tr.bottom.add(w); }

                        // 横向滚动 SKIP 步
                        if (xStart + SKIP <= xStartMax) {
                            int leaveBase = idx;
                            int enterBase = idx + WINDOW_SIZE;
                            for (int t=0; t<SKIP; t++) {
                                sum += colSums[enterBase + t] - colSums[leaveBase + t];
                            }
                        }
                    }

                    // 进度更新
                    done.addAndGet(xStartsThisBand);

                    // 纵向下移 SKIP 行：更新循环缓冲与列和
                    if (zStart + SKIP <= zStartMaxLocal) {
                        for (int add = 0; add < SKIP; add++) {
                            int zLeaveIdx = ringHead;
                            int zEnter = zStart + WINDOW_SIZE + add;
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
                return tr;
            }));
        }

        // 汇总各线程结果
        PriorityQueue<Win> globalTop = new PriorityQueue<>(TOP_K, TOP_HEAP_COMP);
        PriorityQueue<Win> globalBottom = new PriorityQueue<>(TOP_K, BOTTOM_HEAP_COMP);

        for (Future<TaskResult> f : futures) {
            TaskResult tr = f.get();
            // 合并 top
            for (Win w : tr.top) {
                if (globalTop.size() < TOP_K) globalTop.add(w);
                else if (betterThanTopRoot(w, globalTop.peek())) { globalTop.poll(); globalTop.add(w); }
            }
            // 合并 bottom
            for (Win w : tr.bottom) {
                if (globalBottom.size() < TOP_K) globalBottom.add(w);
                else if (betterForBottomThanRoot(w, globalBottom.peek())) { globalBottom.poll(); globalBottom.add(w); }
            }
        }

        // 停止监视器
        finished.set(true);
        pool.shutdown();
        monitor.join();

        // 排序并输出
        List<Win> topList = new ArrayList<>(globalTop);
        // 最多的：数量降序 → 距原点近→远 → Z升 → X升
        topList.sort((a, b) -> {
            if (b.count != a.count) return b.count - a.count;
            long d2a = dist2(a.startX, a.startZ), d2b = dist2(b.startX, b.startZ);
            if (d2a != d2b) return Long.compare(d2a, d2b);
            if (a.startZ != b.startZ) return a.startZ - b.startZ;
            return a.startX - b.startX;
        });

        List<Win> bottomList = new ArrayList<>(globalBottom);
        // 最少的：距原点近→远 → 数量升 → Z升 → X升
        bottomList.sort((a, b) -> {
            long d2a = dist2(a.startX, a.startZ);
            long d2b = dist2(b.startX, b.startZ);
            if (d2a != d2b) return Long.compare(d2a, d2b);
            if (a.count != b.count) return Integer.compare(a.count, b.count);
            if (a.startZ != b.startZ) return Integer.compare(a.startZ, b.startZ);
            return Integer.compare(a.startX, b.startX);
        });

        int totalInWin = WINDOW_SIZE * WINDOW_SIZE;

        System.out.println("\n=== 最多的 " + Math.min(TOP_K, topList.size()) + " 条 ===");
        for (Win w : topList) {
            long wx = (long)w.startX * 16L;
            long wz = (long)w.startZ * 16L;
            double pct = w.count * 100.0 / totalInWin;
            System.out.printf("%d|x,z=%d,%d|X,Z=%d,%d|%dx%d|%.1f%%%n",
                    w.count, wx, wz, w.startX, w.startZ, WINDOW_SIZE, WINDOW_SIZE, pct);
        }

        System.out.println("\n=== 最少的 " + Math.min(TOP_K, bottomList.size()) + " 条（按距原点近→远） ===");
        for (Win w : bottomList) {
            long wx = (long)w.startX * 16L;
            long wz = (long)w.startZ * 16L;
            double pct = w.count * 100.0 / totalInWin;
            System.out.printf("%d|x,z=%d,%d|X,Z=%d,%d|%dx%d|%.1f%%%n",
                    w.count, wx, wz, w.startX, w.startZ, WINDOW_SIZE, WINDOW_SIZE, pct);
        }

        // ======== 结尾统计信息块（你要的格式）========
        int nonOverlap = (width / WINDOW_SIZE) * (height / WINDOW_SIZE); // 不重叠的区域总数
        System.out.println("\n=== 统计信息 ===");
        System.out.println("种子: " + SEED);
        System.out.println("区域大小: " + WINDOW_SIZE + "x" + WINDOW_SIZE);
        System.out.println("计算范围: 区块X[" + MIN_CHUNK_X + " to " + MAX_CHUNK_X + "], Z[" + MIN_CHUNK_Z + " to " + MAX_CHUNK_Z + "]");
        System.out.println("世界坐标范围: X[" + worldMinX + " to " + worldMaxX + "], Z[" + worldMinZ + " to " + worldMaxZ + "]");
        System.out.println("扫描区域总数: " + nonOverlap);
    }
}
