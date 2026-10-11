// Brute-force int8 cosine search over N x 384 vectors, as a straightforward Kotlin/Java
// implementation would do it (flat byte[] + float[] scale, int32 accumulate, top-k heap).
//
// Build & run (JDK 21, no Gradle):
//   javac --add-modules jdk.incubator.vector -d out SearchBench.java
//   java --add-modules jdk.incubator.vector -Xmx3g -cp out SearchBench 500000 384 50
//
// Variants: scalar loop (what ART / Kotlin/Native would roughly execute), scalar loop with
// 4-way unrolling, and jdk.incubator.vector (HotSpot only; NOT available on Android ART).
// Each variant is measured single-threaded and with 4 threads (row partitioning).

import java.util.*;
import java.util.concurrent.*;
import jdk.incubator.vector.*;

public class SearchBench {
    static final VectorSpecies<Byte> B256 = ByteVector.SPECIES_256;
    static final VectorSpecies<Short> S512 = ShortVector.SPECIES_512;
    static final VectorSpecies<Integer> I512 = IntVector.SPECIES_512;

    interface Dot { int dot(byte[] db, int off, byte[] q, int dim); }

    static int dotScalar(byte[] db, int off, byte[] q, int dim) {
        int acc = 0;
        for (int j = 0; j < dim; j++) acc += db[off + j] * q[j];
        return acc;
    }

    static int dotUnrolled(byte[] db, int off, byte[] q, int dim) {
        int a0 = 0, a1 = 0, a2 = 0, a3 = 0;
        for (int j = 0; j < dim; j += 4) {
            a0 += db[off + j] * q[j];
            a1 += db[off + j + 1] * q[j + 1];
            a2 += db[off + j + 2] * q[j + 2];
            a3 += db[off + j + 3] * q[j + 3];
        }
        return a0 + a1 + a2 + a3;
    }

    // int8 x int8 products fit in int16 (|v| <= 127), widen to int32 for accumulation.
    static int dotVector(byte[] db, int off, byte[] q, int dim) {
        IntVector acc = IntVector.zero(I512);
        for (int j = 0; j < dim; j += B256.length()) {
            ByteVector a = ByteVector.fromArray(B256, db, off + j);
            ByteVector b = ByteVector.fromArray(B256, q, j);
            ShortVector p = (ShortVector) a.convertShape(VectorOperators.B2S, S512, 0)
                    .mul(b.convertShape(VectorOperators.B2S, S512, 0));
            acc = acc.add(p.convertShape(VectorOperators.S2I, I512, 0))
                     .add(p.convertShape(VectorOperators.S2I, I512, 1));
        }
        return acc.reduceLanes(VectorOperators.ADD);
    }

    // Scan rows [from, to) and keep the k best (score, idx) in a min-heap.
    static PriorityQueue<long[]> scan(Dot d, byte[] db, float[] scale, byte[] q, int dim, int from, int to, int k) {
        PriorityQueue<long[]> heap = new PriorityQueue<>(k + 1, Comparator.comparingDouble(a -> Float.intBitsToFloat((int) a[0])));
        float worst = Float.NEGATIVE_INFINITY;
        for (int i = from; i < to; i++) {
            float s = d.dot(db, i * dim, q, dim) * scale[i];
            if (heap.size() < k || s > worst) {
                heap.add(new long[]{Float.floatToIntBits(s), i});
                if (heap.size() > k) heap.poll();
                worst = Float.intBitsToFloat((int) heap.peek()[0]);
            }
        }
        return heap;
    }

    static long[] search(Dot d, byte[] db, float[] scale, byte[] q, int n, int dim, int k, int threads, ExecutorService ex) throws Exception {
        List<PriorityQueue<long[]>> parts = new ArrayList<>();
        if (threads == 1) {
            parts.add(scan(d, db, scale, q, dim, 0, n, k));
        } else {
            List<Future<PriorityQueue<long[]>>> fs = new ArrayList<>();
            int step = (n + threads - 1) / threads;
            for (int t = 0; t < threads; t++) {
                int from = t * step, to = Math.min(n, from + step);
                fs.add(ex.submit(() -> scan(d, db, scale, q, dim, from, to, k)));
            }
            for (Future<PriorityQueue<long[]>> f : fs) parts.add(f.get());
        }
        List<long[]> all = new ArrayList<>();
        for (PriorityQueue<long[]> p : parts) all.addAll(p);
        all.sort((a, b) -> Float.compare(Float.intBitsToFloat((int) b[0]), Float.intBitsToFloat((int) a[0])));
        long[] top = new long[Math.min(k, all.size())];
        for (int i = 0; i < top.length; i++) top[i] = all.get(i)[1];
        return top;
    }

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 500_000;
        int dim = args.length > 1 ? Integer.parseInt(args[1]) : 384;
        int k = args.length > 2 ? Integer.parseInt(args[2]) : 50;
        int reps = 10;
        Random rnd = new Random(0);
        byte[] db = new byte[n * dim];
        float[] scale = new float[n];
        for (int i = 0; i < db.length; i++) db[i] = (byte) (rnd.nextInt(255) - 127);
        for (int i = 0; i < n; i++) scale[i] = 1f / (127f * (float) Math.sqrt(dim) * (0.9f + 0.2f * rnd.nextFloat()));
        byte[] q = Arrays.copyOfRange(db, 777 * dim, 778 * dim);
        ExecutorService ex = Executors.newFixedThreadPool(4);

        Map<String, Dot> variants = new LinkedHashMap<>();
        variants.put("scalar", SearchBench::dotScalar);
        variants.put("unrolled4", SearchBench::dotUnrolled);
        variants.put("vector_api", SearchBench::dotVector);

        long[] ref = search(SearchBench::dotScalar, db, scale, q, n, dim, k, 1, ex);
        StringBuilder json = new StringBuilder("{\"n\":" + n + ",\"dim\":" + dim + ",\"k\":" + k
                + ",\"java\":\"" + System.getProperty("java.version") + "\",\"runs\":[");
        boolean first = true;
        for (Map.Entry<String, Dot> v : variants.entrySet()) {
            for (int threads : new int[]{1, 4}) {
                long[] top = null;
                for (int w = 0; w < 5; w++) top = search(v.getValue(), db, scale, q, n, dim, k, threads, ex); // JIT warm-up
                if (top[0] != ref[0] || top[0] != 777) throw new AssertionError("mismatch " + v.getKey());
                double[] ts = new double[reps];
                for (int r = 0; r < reps; r++) {
                    long t0 = System.nanoTime();
                    search(v.getValue(), db, scale, q, n, dim, k, threads, ex);
                    ts[r] = (System.nanoTime() - t0) / 1e6;
                }
                Arrays.sort(ts);
                String row = String.format(Locale.ROOT, "{\"variant\":\"%s\",\"threads\":%d,\"min_ms\":%.2f,\"median_ms\":%.2f}",
                        v.getKey(), threads, ts[0], ts[reps / 2]);
                System.out.println(row);
                json.append(first ? "" : ",").append(row);
                first = false;
            }
        }
        json.append("]}");
        ex.shutdown();
        if (args.length > 3) java.nio.file.Files.writeString(java.nio.file.Path.of(args[3]), json.toString());
    }
}
