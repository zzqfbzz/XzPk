import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Properties;
import java.util.Random;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class slimepk {

    // 判断一个区块是否是史莱姆区块
    public static boolean isSlimeChunk(long worldSeed, int chunkX, int chunkZ) {
        Random rng = new Random(
                worldSeed +
                        (chunkX * chunkX) * 0x4C1906 +
                        (chunkX * 0x5AC0DB) +
                        (chunkZ * chunkZ) * 0x4307A7L +
                        (chunkZ * 0x5F24F) ^ 0x3AD8025F
        );
        return rng.nextInt(10) == 0;
    }

    // 计算指定世界坐标起始的 k2num x k2num 区域内有多少个史莱姆区块
    public static int countSlimeChunks(long worldSeed, int startX, int startZ, int k2num) {
        int count = 0;

        // 遍历 k2num*k2num 区域内的所有区块
        for (int x = startX; x < startX + k2num; x++) {
            for (int z = startZ; z < startZ + k2num; z++) {
                if (isSlimeChunk(worldSeed, x, z)) {
                    count++;
                }
            }
        }

        return count;
    }

    // 更新进度条
    public static void updateProgress(int current, int total) {
        int barWidth = 50;
        double progress = (double) current / total;
        int completed = (int) (barWidth * progress);
        int remaining = barWidth - completed;

        StringBuilder sb = new StringBuilder();
        sb.append("\rProgress: [");
        for (int i = 0; i < completed; i++) {
            sb.append('=');
        }
        for (int i = 0; i < remaining; i++) {
            sb.append(' ');
        }
        sb.append("] ");
        sb.append((int) (progress * 100)).append("% ");
        sb.append(current).append("/").append(total);
        System.out.print(sb.toString());
    }

    public static void main(String[] args) {
        Properties props = new Properties();

        // 从属性文件中读取输入
        try (InputStream input = new FileInputStream("config.properties")) {
            props.load(input);

            long seed = Long.parseLong(props.getProperty("seed"));
            int mix = Integer.parseInt(props.getProperty("mix"));
            int miz = Integer.parseInt(props.getProperty("miz"));
            int max = Integer.parseInt(props.getProperty("max"));
            int maz = Integer.parseInt(props.getProperty("maz"));

            // 设定 k2num 的值
            int k2num = 8; // 可以根据需要调整

            // 除以16并向下取整
            int mixDiv16 = mix / 16;
            int mizDiv16 = miz / 16;
            int maxDiv16 = max / 16;
            int mazDiv16 = maz / 16;

            // 创建一个列表来存储结果
            List<String[]> results = new ArrayList<>();

            // 计算总的区域数
            int totalChunks = (maxDiv16 - mixDiv16 + 2) * (mazDiv16 - mizDiv16 + 2);
            int processedChunks = 0;

            // 遍历并计算
            for (int iz = mizDiv16 - 1; iz < mazDiv16 + 1; iz++) {
                for (int ix = mixDiv16 - 1; ix < maxDiv16 + 1; ix++) {
                    int slimeCount = countSlimeChunks(seed, ix, iz, k2num);
                    results.add(new String[]{String.valueOf(ix*16), String.valueOf(iz*16), String.valueOf(slimeCount)});

                    // 更新进度条
                    processedChunks++;
                    updateProgress(processedChunks, totalChunks);
                }
            }

            // 生成带有时间戳的文件名
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String filename = "slime_"+k2num+"_chunks_" + timestamp + ".csv";

            // 将结果写入 CSV 文件
            try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
                writer.println("X坐标,Z坐标," + k2num + "*" + k2num + "区域史莱姆区块数量,种子:"+seed); // CSV 文件头
                for (String[] result : results) {
                    writer.println(String.join(",", result));
                }
                System.out.println("\n数据已保存到 " + filename);
            } catch (IOException e) {
                System.out.println("写入 CSV 文件时出错: " + e.getMessage());
            }

        } catch (IOException e) {
            System.out.println("读取属性文件时出错: " + e.getMessage());
        }
    }
}
