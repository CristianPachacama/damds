package edu.indiana.soic.spidal.damds;

import com.google.common.base.Optional;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import edu.indiana.soic.spidal.common.*;
import edu.indiana.soic.spidal.configuration.section.DAMDSSection;
import edu.indiana.soic.spidal.damds.threads.ThreadCommunicator;
import edu.indiana.soic.spidal.damds.timing.*;
import mpi.MPI;
import mpi.MPIException;
import net.openhft.lang.io.Bytes;
import org.apache.commons.cli.*;

import java.io.*;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class ProgramWorker {
    // Constants
    private final double INV_SHORT_MAX = 1.0 / Short.MAX_VALUE;
    private final double SHORT_MAX = Short.MAX_VALUE;

    // Calculated Constants
    private double INV_SUM_OF_SQUARE;

    // Arrays
    private double[] preX;
    private double[] BC;
    private double[] MMr;
    private double[] MMAp;

    private double[][] threadPartialBofZ;
    private double[] threadPartialMM;

    private double[] v;

    //Config Settings
    private DAMDSSection config;
    private ByteOrder byteOrder;
    private short[] distances;
    private WeightsWrap1D weights;

    private int BlockSize;

    private int threadId;
    private Range globalThreadRowRange;
    private Range threadLocalRowRange;

    final private RefObj<Integer> refInt = new RefObj<>();
    final private RefObj<Double> refDouble = new RefObj<>();

    private ThreadCommunicator threadComm;
    private Utils utils;
    private Stopwatch mainTimer;

    private BCInternalTimings bcInternalTimings;
    private BCTimings bcTimings;
    private CGLoopTimings cgLoopTimings;
    private CGTimings cgTimings;
    private MMTimings mmTimings;
    private StressInternalTimings stressInternalTimings;
    private StressLoopTimings stressLoopTimings;
    private StressTimings stressTimings;
    private TemperatureLoopTimings temperatureLoopTimings;

    public ProgramWorker(int threadId, ThreadCommunicator comm, DAMDSSection
            config, ByteOrder byteOrder, int blockSize, Stopwatch mainTimer) {
        this.threadId = threadId;
        this.threadComm = comm;
        this.config = config;
        this.byteOrder = byteOrder;
        this.BlockSize = blockSize;
        this.mainTimer = mainTimer;
        utils = new Utils(threadId);

        bcInternalTimings = new BCInternalTimings();
        bcTimings = new BCTimings();
        cgLoopTimings = new CGLoopTimings();
        cgTimings = new CGTimings();
        mmTimings = new MMTimings();
        stressInternalTimings = new StressInternalTimings();
        stressLoopTimings = new StressLoopTimings();
        stressTimings = new StressTimings();
        temperatureLoopTimings = new TemperatureLoopTimings();
    }

    public void setup() {
        final int threadRowCount = ParallelOps.threadRowCounts[threadId];
        final int threadLocalRowStartOffset =
                ParallelOps.threadRowStartOffsets[threadId];
        final int globalThreadRowStartOffset = ParallelOps.procRowStartOffset
                + threadLocalRowStartOffset;
        globalThreadRowRange = new Range(
                globalThreadRowStartOffset, globalThreadRowStartOffset +
                threadRowCount - 1);
        threadLocalRowRange = new Range(
                threadLocalRowStartOffset, (
                threadLocalRowStartOffset + threadRowCount - 1));
    }

    public void run() {
        try {
            setup();

            readDistancesAndWeights(config.isSammon);

            RefObj<Integer> missingDistCount = new RefObj<>();
            DoubleStatistics distanceSummary = calculateStatistics(
                    distances, weights, missingDistCount);
            double missingDistPercent = missingDistCount.getValue() /
                    (Math.pow(config.numberDataPoints, 2));
            INV_SUM_OF_SQUARE = 1.0 / distanceSummary.getSumOfSquare();
            utils.printMessage(
                    "\nDistance summary... \n" + distanceSummary.toString() +
                            "\n  MissingDistPercentage=" +
                            missingDistPercent);

            weights.setAvgDistForSammon(distanceSummary.getAverage());
            changeZeroDistancesToPostiveMin(distances, distanceSummary
                    .getPositiveMin());

            // Allocating point arrays once for all
            allocateArrays();

            if (Strings.isNullOrEmpty(config.initialPointsFile)) {
                generateInitMapping(
                        config.numberDataPoints, config.targetDimension, preX);
            } else {
                readInitMapping(config.initialPointsFile, preX, config
                        .targetDimension);
            }


            double tCur = 0.0;
            double tMax = distanceSummary.getMax() / Math.sqrt(2.0 * config
                    .targetDimension);
            double tMin = config.tMinFactor * distanceSummary.getPositiveMin
                    () / Math.sqrt(2.0 * config.targetDimension);

            generateV(distances, weights, v);
            double preStress = calculateStress(
                    preX, config.targetDimension, tCur, distances, weights,
                    INV_SUM_OF_SQUARE);
            utils.printMessage("\nInitial stress=" + preStress);

            tCur = config.alpha * tMax;

            if (threadId == 0) {
                ParallelOps.worldProcsComm.barrier();
            }
            threadComm.barrier();
            if (threadId == 0) {
                mainTimer.stop();
                utils.printMessage("\nUp to the loop took " + mainTimer.elapsed(
                        TimeUnit.SECONDS) + " seconds");
                mainTimer.start();
            }

            Stopwatch loopTimer = Stopwatch.createStarted();

            int loopNum = 0;
            double diffStress;
            double stress = -1.0;
            RefObj<Integer> outRealCGIterations = new RefObj<>(0);
            RefObj<Integer> cgCount = new RefObj<>(0);
            int smacofRealIterations = 0;
            while (true) {

                temperatureLoopTimings.startTiming(
                        TemperatureLoopTimings.TimingTask.PRE_STRESS);
                preStress = calculateStress(
                        preX, config.targetDimension, tCur, distances, weights,
                        INV_SUM_OF_SQUARE);
                temperatureLoopTimings.endTiming(
                        TemperatureLoopTimings.TimingTask.PRE_STRESS);

                diffStress = config.threshold + 1.0;

                utils.printMessage(
                        String.format(
                                "\nStart of loop %d Temperature (T_Cur) %.5g",
                                loopNum, tCur));

                int itrNum = 0;
                cgCount.setValue(0);
                temperatureLoopTimings.startTiming(
                        TemperatureLoopTimings.TimingTask.STRESS_LOOP);
                while (diffStress >= config.threshold) {

                    zeroOutArray(threadPartialMM);
                    stressLoopTimings.startTiming(
                            StressLoopTimings.TimingTask.BC);
                    calculateBC(
                            preX, config.targetDimension, tCur, distances,
                            weights, BlockSize, BC, threadPartialBofZ,
                            threadPartialMM);
                    stressLoopTimings.endTiming(
                            StressLoopTimings.TimingTask.BC);

                    if (threadId == 0) {
                        // This barrier was necessary for correctness when using
                        // a single mmap file
                        ParallelOps.worldProcsComm.barrier();
                    }
                    threadComm.barrier();

                    // TODO - debugs
                    // System.out.println("Rank: " + ParallelOps
                    // .worldProcRank + " Tid: " + threadId + " afterBC
                    // preX[2600][1]: " + preX[2600*3+1] + " preX[7200][2]: "
                    // + preX[7200*3+2]);

                    stressLoopTimings.startTiming(
                            StressLoopTimings.TimingTask.CG);
                    calculateConjugateGradient(preX, config.targetDimension,
                            config.numberDataPoints,
                            BC,
                            config.cgIter,
                            config.cgErrorThreshold, cgCount,
                            outRealCGIterations, weights,
                            BlockSize, v, MMr, MMAp, threadPartialMM);
                    stressLoopTimings.endTiming(
                            StressLoopTimings.TimingTask.CG);


                    stressLoopTimings.startTiming(
                            StressLoopTimings.TimingTask.STRESS);
                    stress = calculateStress(
                            preX, config.targetDimension, tCur, distances,
                            weights,
                            INV_SUM_OF_SQUARE);
                    stressLoopTimings.endTiming(
                            StressLoopTimings.TimingTask.STRESS);

                    diffStress = preStress - stress;
                    preStress = stress;

                    if ((itrNum % 10 == 0) || (itrNum >= config.stressIter)) {
                        utils.printMessage(
                                String.format(
                                        "  Loop %d Iteration %d Avg CG count " +
                                                "%.5g " +
                                                "Stress " +
                                                "%.5g", loopNum, itrNum,
                                        (cgCount.getValue() * 1.0 / (itrNum +
                                                1)),
                                        stress));
                    }
                    ++itrNum;
                    ++smacofRealIterations;
                }
                temperatureLoopTimings.endTiming(
                        TemperatureLoopTimings.TimingTask.STRESS_LOOP);

                --itrNum;
                if (itrNum >= 0 && !(itrNum % 10 == 0) && !(itrNum >=
                        config.stressIter)) {
                    utils.printMessage(
                            String.format(
                                    "  Loop %d Iteration %d Avg CG count %.5g" +
                                            " " +
                                            "Stress %.5g",
                                    loopNum, itrNum,
                                    (cgCount.getValue() * 1.0 / (itrNum + 1))
                                    , stress));
                }

                utils.printMessage(
                        String.format(
                                "End of loop %d Total Iterations %d Avg CG " +
                                        "count %.5g" +
                                        " Stress %.5g",
                                loopNum, (itrNum + 1),
                                (cgCount.getValue() * 1.0 / (itrNum + 1)),
                                stress));

                if (tCur == 0)
                    break;
                tCur *= config.alpha;
                if (tCur < tMin)
                    tCur = 0;
                ++loopNum;

                /* Note - quick way to test programs without running full
                * number of temperature loops */
                if (config.maxtemploops > 0 && loopNum == config.maxtemploops) {
                    break;
                }
            }
            loopTimer.stop();

            double QoR1 = stress / (config.numberDataPoints * (config
                    .numberDataPoints - 1) / 2);
            double QoR2 = QoR1 / (distanceSummary.getAverage() *
                    distanceSummary.getAverage());

            utils.printMessage(
                    String.format(
                            "Normalize1 = %.5g Normalize2 = %.5g", QoR1, QoR2));
            utils.printMessage(
                    String.format(
                            "Average of Delta(original distance) = %.5g",
                            distanceSummary.getAverage()));

            Double finalStress = calculateStress(
                    preX, config.targetDimension, tCur, distances, weights,
                    INV_SUM_OF_SQUARE);

            if (threadId == 0) {
                mainTimer.stop();
            }

            utils.printMessage("Finishing DAMDS run ...");
            long totalTime = mainTimer.elapsed(TimeUnit.MILLISECONDS);
            long temperatureLoopTime = loopTimer.elapsed(TimeUnit.MILLISECONDS);
            utils.printMessage(
                    String.format(
                            "  Total Time: %s (%d ms) Loop Time: %s (%d ms)",
                            formatElapsedMillis(totalTime), totalTime,
                            formatElapsedMillis(temperatureLoopTime),
                            temperatureLoopTime));
            utils.printMessage("  Total Loops: " + loopNum);
            utils.printMessage("  Total Iterations: " + smacofRealIterations);
            utils.printMessage(
                    String.format(
                            "  Total CG Iterations: %d Avg. CG Iterations: %" +
                                    ".5g",
                            outRealCGIterations.getValue(),
                            (outRealCGIterations.getValue() * 1.0) /
                                    smacofRealIterations));
            utils.printMessage("  Final Stress:\t" + finalStress);

            // TODO - fix print timings
            /*printTimings(totalTime, temperatureLoopTime);*/
        } catch (MPIException e) {
            utils.printAndThrowRuntimeException(new RuntimeException(e));
        } catch (InterruptedException | BrokenBarrierException e) {
            e.printStackTrace();
        }
    }

    private void allocateArrays() {
        // Allocating point arrays once for all
        final int numberDataPoints = config.numberDataPoints;
        final int targetDimension = config.targetDimension;

        preX = new double[numberDataPoints * targetDimension];
        BC = new double[numberDataPoints * targetDimension];
        MMr = new double[numberDataPoints * targetDimension];
        MMAp = new double[numberDataPoints * targetDimension];
        final int threadRowCount = ParallelOps.threadRowCounts[threadId];
        threadPartialBofZ = new double[threadRowCount][ParallelOps
                .globalColCount];
        threadPartialMM = new double[threadRowCount
                * config.targetDimension];
        v = new double[threadRowCount];
    }

    private void zeroOutArray(double[] a) {
        Arrays.fill(a, 0.0d);
    }

    private void zeroOutArray(double[][] a) {
        for (int j = 0; j < ParallelOps.threadRowCounts[threadId]; ++j) {
            Arrays.fill(a[j], 0.0d);
        }
    }

    private void changeZeroDistancesToPostiveMin(
            short[] distances, double positiveMin) {
        double tmpD;
        for (int i = 0; i < distances.length; ++i) {
            tmpD = distances[i] * INV_SHORT_MAX;
            if (tmpD < positiveMin && tmpD >= 0.0) {
                distances[i] = (short) (positiveMin * SHORT_MAX);
            }
        }
    }

    private static long[] getTemperatureLoopTimeDistribution(
            long temperatureLoopTime) throws MPIException {
        LongBuffer mpiOnlyTimingBuffer = ParallelOps.mpiOnlyBuffer;
        mpiOnlyTimingBuffer.position(0);
        mpiOnlyTimingBuffer.put(temperatureLoopTime);
        ParallelOps.gather(mpiOnlyTimingBuffer, 1, 0);
        long[] mpiOnlyTimingArray = new long[ParallelOps.worldProcsCount];
        mpiOnlyTimingBuffer.position(0);
        mpiOnlyTimingBuffer.get(mpiOnlyTimingArray);
        return mpiOnlyTimingArray;
    }

    private void readInitMapping(
            String initialPointsFile, double[] preX, int dimension)
            throws BrokenBarrierException, InterruptedException {
        if (threadId == 0) {
            try (BufferedReader br = Files
                    .newBufferedReader(Paths.get(initialPointsFile),
                            Charset.defaultCharset())) {
                String line;
                Pattern pattern = Pattern.compile("[\t]");
                int row = 0;
                while ((line = br.readLine()) != null) {
                    if (Strings.isNullOrEmpty(line)) {
                        continue; // continue on empty lines - "while" will
                    }
                    // break on null anyway;

                    String[] splits = pattern.split(line.trim());

                    for (int i = 0; i < dimension; ++i) {
                        preX[row + i] = Double.parseDouble(splits[i].trim());
                    }
                    row += dimension;
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        threadComm.barrier();
        threadComm.bcastDoubleArrayOverThreads(threadId, preX, 0);
    }

    public static String formatElapsedMillis(long elapsed) {
        String format = "%dd:%02dH:%02dM:%02dS:%03dmS";
        short millis = (short) (elapsed % (1000.0));
        elapsed = (elapsed - millis) / 1000; // remaining elapsed in seconds
        byte seconds = (byte) (elapsed % 60.0);
        elapsed = (elapsed - seconds) / 60; // remaining elapsed in minutes
        byte minutes = (byte) (elapsed % 60.0);
        elapsed = (elapsed - minutes) / 60; // remaining elapsed in hours
        byte hours = (byte) (elapsed % 24.0);
        long days = (elapsed - hours) / 24; // remaining elapsed in days
        return String.format(format, days, hours, minutes, seconds, millis);
    }

    private static void writeOuput(double[] x, int vecLen, String outputFile)
            throws IOException {
        PrintWriter writer = new PrintWriter(new FileWriter(outputFile));
        int N = x.length / vecLen;

        DecimalFormat format = new DecimalFormat("#.##########");
        for (int i = 0; i < N; i++) {
            int index = i * vecLen;
            writer.print(String.valueOf(i) + '\t'); // print ID.
            for (int j = 0; j < vecLen; j++) {
                writer.print(format.format(x[index + j]) + '\t'); // print
                // configuration
                // of each axis.
            }
            writer.println("1"); // print label value, which is ONE for all
            // data.
        }
        writer.flush();
        writer.close();

    }

    private static void writeOuput(double[] X, int vecLen, String labelFile,
                                   String outputFile) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(labelFile));
        String line;
        String parts[];
        Map<String, Integer> labels = new HashMap<>();
        while ((line = reader.readLine()) != null) {
            parts = line.split(" ");
            if (parts.length < 2) {
                // Don't need to throw an error because this is the last part of
                // the computation
                System.out.println("ERROR: Invalid label");
            }
            labels.put(parts[0].trim(), Integer.valueOf(parts[1]));
        }
        reader.close();

        File file = new File(outputFile);
        PrintWriter writer = new PrintWriter(new FileWriter(file));

        int N = X.length / 3;

        DecimalFormat format = new DecimalFormat("#.##########");
        for (int i = 0; i < N; i++) {
            int index = i * vecLen;
            writer.print(String.valueOf(i) + '\t'); // print ID.
            for (int j = 0; j < vecLen; j++) {
                writer.print(format.format(X[index + j]) + '\t'); // print
                // configuration
                // of each axis.
            }
            /* TODO Fix bug here - it's from Ryan's code*/
            /*writer.println(labels.get(String.valueOf(ids[i]))); // print
            label*/
            // value, which
            // is
            // ONE for all data.
        }
        writer.flush();
        writer.close();
    }

    private void generateV(
            short[] distances, WeightsWrap1D weights, double[] v) {
        zeroOutArray(v);
        int threadRowCount = ParallelOps.threadRowCounts[threadId];

        int rowOffset = ParallelOps.threadRowStartOffsets[threadId] +
                ParallelOps.procRowStartOffset;
        for (int threadLocalRow = 0; threadLocalRow < threadRowCount;
             ++threadLocalRow) {
            int globalRow = threadLocalRow + rowOffset;
            for (int globalCol = 0; globalCol < ParallelOps.globalColCount;
                 ++globalCol) {
                if (globalRow == globalCol) continue;

                double origD = distances[threadLocalRow * ParallelOps
                        .globalColCount + globalCol] * INV_SHORT_MAX;
                double weight = weights.getWeight(threadLocalRow, globalCol);

                if (origD < 0 || weight == 0) {
                    continue;
                }

                v[threadLocalRow] += weight;
            }
            v[threadLocalRow] += 1;
        }

    }


    private void calculateConjugateGradient(
            double[] preX, int targetDimension, int numPoints, double[] BC,
            int cgIter, double cgThreshold, RefObj<Integer> outCgCount,
            RefObj<Integer> outRealCGIterations, WeightsWrap1D weights,
            int blockSize, double[] v, double[] MMr, double[] MMAp,
            double[] threadPartialMM)

            throws MPIException, BrokenBarrierException, InterruptedException {


        zeroOutArray(threadPartialMM);
        cgTimings.startTiming(CGTimings.TimingTask.MM);
        calculateMM(preX, targetDimension, numPoints, weights, blockSize, v,
                MMr, threadPartialMM);
        cgTimings.endTiming(CGTimings.TimingTask.MM);

        if (threadId == 0) {
            // This barrier was necessary for correctness when using
            // a single mmap file
            ParallelOps.worldProcsComm.barrier();
        }
        threadComm.barrier();

        // TODO - debugs
        //System.out.println("Rank: " + ParallelOps.worldProcRank + " Tid: "
        // + threadId + " inCG after 1st MM MMr[2600][1]: " + MMr[2600*3+1] +
        // " MMr[7200][2]: " + MMr[7200*3+2]);

        int iOffset;

        for (int i = 0; i < numPoints; ++i) {
            iOffset = i * targetDimension;
            for (int j = 0; j < targetDimension; ++j) {
                BC[iOffset + j] -= MMr[iOffset + j];
                MMr[iOffset + j] = BC[iOffset + j];
            }
        }

        int cgCount = 0;
        cgTimings.startTiming(CGTimings.TimingTask.INNER_PROD);
        double rTr = innerProductCalculation(MMr);
        cgTimings.endTiming(CGTimings.TimingTask.INNER_PROD);
        // Adding relative value test for termination as suggested by Dr. Fox.
        double testEnd = rTr * cgThreshold;

        cgTimings.startTiming(CGTimings.TimingTask.CG_LOOP);
        while (cgCount < cgIter) {
            cgCount++;
            outRealCGIterations.setValue(outRealCGIterations.getValue() + 1);

            //calculate alpha
            zeroOutArray(threadPartialMM);
            cgLoopTimings.startTiming(CGLoopTimings.TimingTask.MM);
            calculateMM(BC, targetDimension, numPoints, weights, blockSize, v,
                    MMAp, threadPartialMM);
            cgLoopTimings.endTiming(CGLoopTimings.TimingTask.MM);
            if (threadId == 0) {
                ParallelOps.worldProcsComm.barrier();
            }
            threadComm.barrier();

            cgLoopTimings.startTiming(CGLoopTimings.TimingTask.INNER_PROD_PAP);
            double alpha = rTr / innerProductCalculation(BC, MMAp);
            cgLoopTimings.endTiming(CGLoopTimings.TimingTask.INNER_PROD_PAP);

            //update Xi to Xi+1
            for (int i = 0; i < numPoints; ++i) {
                iOffset = i * targetDimension;
                for (int j = 0; j < targetDimension; ++j) {
                    preX[iOffset + j] += alpha * BC[iOffset + j];
                }
            }

            if (rTr < testEnd) {
                break;
            }

            //update ri to ri+1
            for (int i = 0; i < numPoints; ++i) {
                iOffset = i * targetDimension;
                for (int j = 0; j < targetDimension; ++j) {
                    MMr[iOffset + j] -= alpha * MMAp[iOffset + j];
                }
            }

            //calculate beta
            cgLoopTimings.startTiming(CGLoopTimings.TimingTask.INNER_PROD_R);
            double rTr1 = innerProductCalculation(MMr);
            cgLoopTimings.endTiming(CGLoopTimings.TimingTask.INNER_PROD_R);
            double beta = rTr1 / rTr;
            rTr = rTr1;

            //update pi to pi+1
            for (int i = 0; i < numPoints; ++i) {
                iOffset = i * targetDimension;
                for (int j = 0; j < targetDimension; ++j) {
                    BC[iOffset + j] = MMr[iOffset + j] + beta * BC[iOffset + j];
                }
            }

        }
        cgTimings.endTiming(CGTimings.TimingTask.CG_LOOP);
        outCgCount.setValue(outCgCount.getValue() + cgCount);
    }

    private void calculateMM(
            double[] x, int targetDimension, int numPoints, WeightsWrap1D
            weights,
            int blockSize, double[] v, double[] outMM,
            double[] internalPartialMM)
            throws MPIException, BrokenBarrierException, InterruptedException {

        mmTimings.startTiming(MMTimings.TimingTask.MM_INTERNAL, threadId);
        calculateMMInternal(x, targetDimension, numPoints, weights,
                blockSize, v, internalPartialMM);
        mmTimings.endTiming(MMTimings.TimingTask.MM_INTERNAL, threadId);

        // TODO - debugs
        /*if (ParallelOps.worldProcRank == 0 && threadId == 1) {
            System.out.println("Rank: " + ParallelOps.worldProcRank + " Tid: " +
                    "\n" + threadId + " inMM after MMInternal\n" +
                    " internalPartialMM[2600][1]: " + internalPartialMM[(2600
                    - 2500) * 3 + 1]);
        }

        if (ParallelOps.worldProcRank == 1 && threadId == 0) {
            System.out.println("Rank: " + ParallelOps.worldProcRank + " Tid: " +
                    "\n" + threadId + " inMM after MMInternal\n" +
                    " internalPartialMM[7200][2]: " + internalPartialMM[(7200
                    - 5000) * 3 + 2]);
        }

        if (ParallelOps.worldProcRank == 1 && threadId == 1) {
            System.out.println("Rank: " + ParallelOps.worldProcRank + " Tid: " +
                    "\n" + threadId + " inMM after MMInternal\n" +
                    " internalPartialMM[8013][2]: " + internalPartialMM[(8013
                    - 7500) * 3 + 2]);
        }*/

        mmTimings.startTiming(MMTimings.TimingTask.MM_MERGE, 0);
        threadComm
                .collect(threadLocalRowRange.getStartIndex(), internalPartialMM,
                        ParallelOps.mmapXWriteBytes);
        threadComm.barrier();
        mmTimings.endTiming(MMTimings.TimingTask.MM_MERGE, 0);

        // TODO - debugs
        /*if (ParallelOps.worldProcRank == 0 && threadId == 1) {
            System.out.println("++Rank: " + ParallelOps.worldProcRank + " " +
                    "Tid: " +
                    "\n" + threadId + " inMM after MMInternal\n" +
                    " mmapXWriteBytes[2600][1]: " + ParallelOps
                    .mmapXWriteBytes.readDouble(2600 * 3 + 1));
        }
        threadComm.barrier();

        if (ParallelOps.worldProcRank == 1 && threadId == 0) {
            System.out.println("++Rank: " + ParallelOps.worldProcRank + " " +
                    "Tid: " +
                    "\n" + threadId + " inMM after MMInternal\n" +
                    " mmapXWriteBytes[7200][2]: " + ParallelOps
                    .mmapXWriteBytes.readDouble((7200 - 5000) * 3 + 2));
        }
        threadComm.barrier();

        if (ParallelOps.worldProcRank == 1 && threadId == 1) {
            System.out.println("++Rank: " + ParallelOps.worldProcRank + " " +
                    "Tid: " +
                    "\n" + threadId + " inMM after MMInternal\n" +
                    " mmapXWriteBytes[8013][2]: " + ParallelOps
                    .mmapXWriteBytes.readDouble((8013 - 5000) * 3 + 2));
        }
        threadComm.barrier();*/

        if (ParallelOps.worldProcsCount > 1) {
            if (threadId == 0) {
                // Important barrier here - as we need to make sure writes
                // are done to the mmap file

                // it's sufficient to wait on ParallelOps.mmapProcComm, but
                // it's cleaner for timings
                // if we wait on the whole world
                ParallelOps.worldProcsComm.barrier();

                if (ParallelOps.isMmapLead) {
                    mmTimings.startTiming(MMTimings.TimingTask.COMM, 0);
                    ParallelOps.partialXAllGather();
                    mmTimings.endTiming(MMTimings.TimingTask.COMM, 0);
                }
                // Each process in a memory group waits here.
                // It's not necessary to wait for a process
                // in another memory map group, hence the use of mmapProcComm.
                // However it's cleaner for any timings to have everyone sync
                // here,
                // so will use worldProcsComm instead.
                ParallelOps.worldProcsComm.barrier();
            }
            threadComm.barrier();
        }
        mmTimings.startTiming(MMTimings.TimingTask.MM_EXTRACT, 0);
        threadComm.copy(ParallelOps.worldProcsCount > 1
                        ? ParallelOps.fullXBytes
                        : ParallelOps.mmapXWriteBytes, outMM,
                ParallelOps.globalColCount * targetDimension);
        threadComm.barrier();
        mmTimings.endTiming(MMTimings.TimingTask.MM_EXTRACT, 0);

        // TODO - debugs
        /*System.out.println("**Rank: " + ParallelOps.worldProcRank + " Tid: "
                + threadId + " inMM after MMInternal outMM[2600][1]: " +
                outMM[2600 * 3 + 1] + " outMM[7200][2]: " + outMM[7200 * 3 +
                2] + " outMM[8013][2]: " + outMM[8013 * 3 + 2]);*/
    }

    private void calculateMMInternal(
            double[] x, int targetDimension, int numPoints,
            WeightsWrap1D weights, int blockSize, double[] v, double[] outMM) {

        MatrixUtils.matrixMultiplyWithThreadOffset(weights, v, x,
                globalThreadRowRange.getLength(), targetDimension, numPoints,
                blockSize, 0, globalThreadRowRange.getStartIndex(), outMM);
    }


    private static double innerProductCalculation(double[] a, double[] b) {
        double sum = 0;
        if (a.length > 0) {
            for (int i = 0; i < a.length; ++i) {
                sum += a[i] * b[i];
            }
        }
        return sum;
    }

    private static double innerProductCalculation(double[] a) {
        double sum = 0.0;
        if (a.length > 0) {
            for (double anA : a) {
                sum += anA * anA;
            }
        }
        return sum;
    }

    private void calculateBC(
            double[] preX, int targetDimension, double tCur, short[] distances,
            WeightsWrap1D weights, int blockSize, double[] BC,
            double[][] threadPartialBCInternalBofZ,
            double[] threadPartialBCInternalMM)
            throws MPIException, InterruptedException, BrokenBarrierException {

        bcTimings.startTiming(BCTimings.TimingTask.BC_INTERNAL);
        calculateBCInternal(
                preX, targetDimension, tCur, distances, weights, blockSize,
                threadPartialBCInternalBofZ, threadPartialBCInternalMM);
        bcTimings.endTiming(
                BCTimings.TimingTask.BC_INTERNAL, 0);

        // TODO - debugs
        if (ParallelOps.worldProcRank == 0 && threadId == 1) {
            System.out.println("Rank: " + ParallelOps.worldProcRank + " Tid: " +
                    "\n" + threadId + " inBC after BCInternal\n" +
                    " threadPartialBCInternalMM[2600][1]: " + threadPartialBCInternalMM[(2600
                    - 2500) * 3 + 1]);
        }

        if (ParallelOps.worldProcRank == 1 && threadId == 0) {
            System.out.println("Rank: " + ParallelOps.worldProcRank + " Tid: " +
                    "\n" + threadId + " inBC after BCInternal\n" +
                    " threadPartialBCInternalMM[7200][2]: " + threadPartialBCInternalMM[(7200
                    - 5000) * 3 + 2]);
        }

        if (ParallelOps.worldProcRank == 1 && threadId == 1) {
            System.out.println("Rank: " + ParallelOps.worldProcRank + " Tid: " +
                    "\n" + threadId + " inBC after BCInternal\n" +
                    " threadPartialBCInternalMM[8013][2]: " + threadPartialBCInternalMM[(8013
                    - 7500) * 3 + 2]);
        }

        bcTimings.startTiming(BCTimings.TimingTask.BC_MERGE);
        threadComm.collect(threadLocalRowRange.getStartIndex()
                *targetDimension*Double.BYTES,
                threadPartialBCInternalMM, ParallelOps.mmapXWriteBytes);
        threadComm.barrier();
        bcTimings.endTiming(BCTimings.TimingTask.BC_MERGE, 0);

        // TODO - debugs
        if (ParallelOps.worldProcRank == 0 && threadId == 1) {
            System.out.println("++Rank: " + ParallelOps.worldProcRank + " " +
                    "Tid: " +
                    "\n" + threadId + " inBC after BCInternal\n" +
                    " mmapXWriteBytes[2600][1]: " + ParallelOps
                    .mmapXWriteBytes.readDouble((2600 * 3 + 1)*Double.BYTES));
        }
        threadComm.barrier();

        if (ParallelOps.worldProcRank == 1 && threadId == 0) {
            System.out.println("++Rank: " + ParallelOps.worldProcRank + " " +
                    "Tid: " +
                    "\n" + threadId + " inBC after BCInternal\n" +
                    " mmapXWriteBytes[7200][2]: " + ParallelOps
                    .mmapXWriteBytes.readDouble(((7200 - 5000) * 3 + 2))
                    *Double.BYTES);
        }
        threadComm.barrier();

        if (ParallelOps.worldProcRank == 1 && threadId == 1) {
            System.out.println("++Rank: " + ParallelOps.worldProcRank + " " +
                    "Tid: " +
                    "\n" + threadId + " inBC after BCInternal\n" +
                    " mmapXWriteBytes[8013][2]: " + ParallelOps
                    .mmapXWriteBytes.readDouble(((8013 - 5000) * 3 + 2))
                    *Double.BYTES);
        }
        threadComm.barrier();


        if (ParallelOps.worldProcsCount > 1) {
            if (threadId == 0) {
                // Important barrier here - as we need to make sure writes
                // are done to the mmap file

                // it's sufficient to wait on ParallelOps.mmapProcComm, but
                // it's cleaner for timings
                // if we wait on the whole world
                ParallelOps.worldProcsComm.barrier();

                if (ParallelOps.isMmapLead) {
                    bcTimings.startTiming(BCTimings.TimingTask.COMM);
                    ParallelOps.partialXAllGather();
                    bcTimings.endTiming(BCTimings.TimingTask.COMM, 0);
                }
                // Each process in a memory group waits here.
                // It's not necessary to wait for a process
                // in another memory map group, hence the use of
                // mmapProcComm.
                // However it's cleaner for any timings to have everyone sync
                // here, so will use worldProcsComm instead.
                ParallelOps.worldProcsComm.barrier();
            }
            threadComm.barrier();
        }
        bcTimings.startTiming(BCTimings.TimingTask.BC_EXTRACT);
        threadComm.copy(ParallelOps.worldProcsCount > 1
                        ? ParallelOps.fullXBytes
                        : ParallelOps.mmapXWriteBytes, BC,
                ParallelOps.globalColCount * targetDimension);
        threadComm.barrier();
        bcTimings.endTiming(BCTimings.TimingTask.BC_EXTRACT, 0);
    }

    private void calculateBCInternal(
            double[] preX, int targetDimension, double tCur, short[] distances,
            WeightsWrap1D weights, int blockSize, double[][] internalBofZ,
            double[] outMM) {

        bcInternalTimings.startTiming(BCInternalTimings.TimingTask.BOFZ);
        calculateBofZ(preX, targetDimension, tCur,
                distances, weights, internalBofZ);
        bcInternalTimings.endTiming(BCInternalTimings.TimingTask.BOFZ);

        // Next we can calculate the BofZ * preX.
        bcInternalTimings.startTiming(BCInternalTimings.TimingTask.MM);
        MatrixUtils.matrixMultiply(internalBofZ, preX,
                globalThreadRowRange.getLength(), targetDimension,
                ParallelOps.globalColCount, blockSize, outMM);
        bcInternalTimings.endTiming(BCInternalTimings.TimingTask.MM);
    }

    private void calculateBofZ(
            double[] preX, int targetDimension, double tCur, short[]
            distances, WeightsWrap1D weights,
            double[][] outBofZ) {

        int threadRowCount = globalThreadRowRange.getLength();

        double vBlockValue = -1;

        double diff = 0.0;
        if (tCur > 10E-10) {
            diff = Math.sqrt(2.0 * targetDimension) * tCur;
        }

        double[] outBofZLocalRow;
        double origD, weight, dist;

        final int globalColCount = ParallelOps.globalColCount;
        final int globalRowOffset = globalThreadRowRange.getStartIndex();
        int globalRow;
        for (int threadLocalRow = 0; threadLocalRow < threadRowCount;
             ++threadLocalRow) {
            globalRow = threadLocalRow + globalRowOffset;
            outBofZLocalRow = outBofZ[threadLocalRow];
            outBofZLocalRow[globalRow] = 0;
            for (int globalCol = 0; globalCol < ParallelOps.globalColCount;
                 globalCol++) {
                 /* B_ij = - w_ij * delta_ij / d_ij(Z), if (d_ij(Z) != 0) 0,
				 * otherwise v_ij = - w_ij.
				 *
				 * Therefore, B_ij = v_ij * delta_ij / d_ij(Z). 0 (if d_ij
				 * (Z) >=
				 * small threshold) --> the actual meaning is (if d_ij(Z) == 0)
				 * BofZ[i][j] = V[i][j] * deltaMat[i][j] / CalculateDistance
				 * (ref
				 * preX, i, j);*/

                // this is for the i!=j case. For i==j case will be calculated
                // separately (see above).
                if (globalRow == globalCol) continue;


                origD = distances[threadLocalRow * globalColCount +
                        globalCol] * INV_SHORT_MAX;
                weight = weights.getWeight(threadLocalRow, globalCol);

                if (origD < 0 || weight == 0) {
                    continue;
                }

                dist = calculateEuclideanDist(preX, globalRow, globalCol,
                        targetDimension);
                if (dist >= 1.0E-10 && diff < origD) {
                    outBofZLocalRow[globalCol] = (weight * vBlockValue *
                            (origD - diff) / dist);
                } else {
                    outBofZLocalRow[globalCol] = 0;
                }
                outBofZLocalRow[globalRow] -= outBofZLocalRow[globalCol];
            }
        }
    }

    private static void extractPoints(
            Bytes bytes, int numPoints, int dimension, double[] to) {
        int pos = 0;
        int offset;
        for (int i = 0; i < numPoints; ++i) {
            offset = i * dimension;
            for (int j = 0; j < dimension; ++j) {
                bytes.position(pos);
                to[offset + j] = bytes.readDouble(pos);
                pos += Double.BYTES;
            }
        }
    }

    private static void mergePartials(double[][] partials, double[] result) {
        int offset = 0;
        for (double[] partial : partials) {
            System.arraycopy(partial, 0, result, offset, partial.length);
            offset += partial.length;
        }
    }

    private static void mergePartials(
            double[][] partials, Bytes result) {
        result.position(0);
        for (double[] partial : partials) {
            for (double aPartial : partial) {
                result.writeDouble(aPartial);
            }
        }
    }

    private double calculateStress(
            double[] preX, int targetDimension, double tCur, short[] distances,
            WeightsWrap1D weights, double invSumOfSquareDist)
            throws MPIException, BrokenBarrierException, InterruptedException {

        refDouble.setValue(calculateStressInternal(threadId, preX,
                targetDimension, tCur,
                distances, weights));
        threadComm.sumDoublesOverThreads(threadId, refDouble);

        if (ParallelOps.worldProcsCount > 1 && threadId == 0) {
            double stress = refDouble.getValue();
            // Write thread local reduction to shared memory map
            ParallelOps.mmapSWriteBytes.position(0);
            ParallelOps.mmapSWriteBytes.writeDouble(stress);

            // Important barrier here - as we need to make sure writes are done
            // to the mmap file.
            // It's sufficient to wait on ParallelOps.mmapProcComm,
            // but it's cleaner for timings if we wait on the whole world
            ParallelOps.worldProcsComm.barrier();
            if (ParallelOps.isMmapLead) {
                // Node local reduction using shared memory maps
                ParallelOps.mmapSReadBytes.position(0);
                stress = 0.0;
                for (int i = 0; i < ParallelOps.mmapProcsCount; ++i) {
                    stress += ParallelOps.mmapSReadBytes.readDouble();
                }
                ParallelOps.mmapSWriteBytes.position(0);
                ParallelOps.mmapSWriteBytes.writeDouble(stress);

                // Leaders participate in MPI AllReduce
                stressTimings.startTiming(StressTimings.TimingTask.COMM, 0);
                ParallelOps.partialSAllReduce(MPI.SUM);
                stressTimings.endTiming(StressTimings.TimingTask.COMM, 0);
            }

            // Each process in a memory group waits here.
            // It's not necessary to wait for a process
            // in another memory map group, hence the use of mmapProcComm.
            // However it's cleaner for any timings to have everyone sync here,
            // so will use worldProcsComm instead.
            ParallelOps.worldProcsComm.barrier();
            ParallelOps.mmapSReadBytes.position(0);
            stress = ParallelOps.mmapSReadBytes.readDouble();
            refDouble.setValue(stress);
        }

        threadComm.barrier();
        threadComm.bcastDoubleOverThreads(threadId, refDouble, 0);
        return refDouble.getValue() * invSumOfSquareDist;
    }

    private double calculateStressInternal(
            int threadIdx, double[] preX, int targetDim, double tCur, short[]
            distances, WeightsWrap1D weights) {

        stressInternalTimings.startTiming(StressInternalTimings.TimingTask
                .COMP, threadIdx);
        double sigma = 0.0;
        double diff = 0.0;
        if (tCur > 10E-10) {
            diff = Math.sqrt(2.0 * targetDim) * tCur;
        }

        int threadRowCount = globalThreadRowRange.getLength();
        final int globalRowOffset = globalThreadRowRange.getStartIndex();

        int globalColCount = ParallelOps.globalColCount;
        int globalRow;
        double origD, weight, euclideanD;
        double heatD, tmpD;
        for (int threadLocalRow = 0; threadLocalRow < threadRowCount;
             ++threadLocalRow) {
            globalRow = threadLocalRow + globalRowOffset;
            for (int globalCol = 0; globalCol < globalColCount; globalCol++) {
                origD = distances[threadLocalRow * globalColCount + globalCol]
                        * INV_SHORT_MAX;
                weight = weights.getWeight(threadLocalRow, globalCol);

                if (origD < 0 || weight == 0) {
                    continue;
                }

                euclideanD = globalRow != globalCol ? calculateEuclideanDist(
                        preX, globalRow, globalCol, targetDim) : 0.0;

                heatD = origD - diff;
                tmpD = origD >= diff ? heatD - euclideanD : -euclideanD;
                sigma += weight * tmpD * tmpD;
            }
        }
        stressInternalTimings.endTiming(StressInternalTimings.TimingTask
                .COMP, threadIdx);
        return sigma;
    }

    /*private static double calculateEuclideanDist(
        double[] v, double[] w, int targetDim) {
        double dist = 0.0;
        for (int k = 0; k < targetDim; k++) {
            try {
                double diff = v[k] - w[k];
                dist += diff * diff;
            } catch (IndexOutOfBoundsException e){
                // Usually this should not happen, also this is not
                // necessary to catch, but it was found that some parent block
                // in HJ hides this error if/when it happens, so explicitly
                // printing it here.
                e.printStackTrace();
            }
        }

        dist = Math.sqrt(dist);
        return dist;
    }*/

    public double calculateEuclideanDist(double[] v, int i, int j, int d) {
        double t = 0.0;
        double e;
        i = d * i;
        j = d * j;
        for (int k = 0; k < d; ++k) {
            e = v[i + k] - v[j + k];
            t += e * e;
        }
        return Math.sqrt(t);
    }

    private void generateInitMapping(
            int numPoints, int targetDim, double[] preX)
            throws MPIException, BrokenBarrierException, InterruptedException {

        Bytes fullBytes = ParallelOps.fullXBytes;
        if (threadId == 0) {
            if (ParallelOps.worldProcRank == 0) {
                int pos = 0;
                // Use Random class for generating random initial mapping
                // solution.

                Random rand = new Random(System.currentTimeMillis());
                for (int i = 0; i < numPoints; i++) {
                    for (int j = 0; j < targetDim; j++) {
                        fullBytes.position(pos);
                        fullBytes.writeDouble(rand.nextBoolean()
                                ? rand.nextDouble()
                                : -rand.nextDouble());
                        pos += Double.BYTES;
                    }
                }

            }

            if (ParallelOps.worldProcsCount > 1) {
                // Broadcast initial mapping to others
                ParallelOps.broadcast(ParallelOps.fullXByteBuffer,
                        numPoints * targetDim * Double.BYTES, 0);
            }
        }
        threadComm.barrier();
        extractPoints(fullBytes, numPoints, targetDim, preX);
    }

    private DoubleStatistics calculateStatistics(
            short[] distances, WeightsWrap1D weights, RefObj<Integer>
            missingDistCount)
            throws MPIException, BrokenBarrierException, InterruptedException {

        DoubleStatistics distanceSummary =
                calculateStatisticsInternal(distances, weights, refInt);
        threadComm.sumDoubleStatisticsOverThreads(threadId, distanceSummary);
        threadComm.sumIntOverThreads(threadId, refInt);

        if (ParallelOps.worldProcsCount > 1 && threadId == 0) {
            distanceSummary = ParallelOps.allReduce(distanceSummary);
            refInt.setValue(ParallelOps.allReduce(refInt.getValue()));
        }
        threadComm.barrier();
        threadComm.bcastDoubleStatisticsOverThreads(threadId,
                distanceSummary, 0);
        threadComm.bcastIntOverThreads(threadId, refInt, 0);
        missingDistCount.setValue(refInt.getValue());
        return distanceSummary;
    }

    private static TransformationFunction loadFunction(String classFile) {
        ClassLoader classLoader = ProgramLRT.class.getClassLoader();
        try {
            Class aClass = classLoader.loadClass(classFile);
            return (TransformationFunction) aClass.newInstance();
        } catch (ClassNotFoundException | InstantiationException |
                IllegalAccessException e) {
            throw new RuntimeException("Failed to load class: " + classFile, e);
        }
    }

    private void readDistancesAndWeights(boolean isSammon) {
        TransformationFunction function;
        if (!Strings.isNullOrEmpty(config.transformationFunction)) {
            function = loadFunction(config.transformationFunction);
        } else {
            function = (config.distanceTransform != 1.0
                    ? (d -> Math.pow(d, config.distanceTransform))
                    : null);
        }


        int elementCount = globalThreadRowRange.getLength() * ParallelOps
                .globalColCount;
        distances = new short[elementCount];
        if (config.repetitions == 1) {
            BinaryReader1D.readRowRange(config.distanceMatrixFile,
                    globalThreadRowRange, ParallelOps.globalColCount, byteOrder,
                    true, function, distances);
        } else {
            BinaryReader1D.readRowRange(config.distanceMatrixFile,
                    globalThreadRowRange, ParallelOps.globalColCount, byteOrder,
                    true, function, config.repetitions, distances);
        }

        if (!Strings.isNullOrEmpty(config.weightMatrixFile)) {
            short[] w = null;
            function = !Strings.isNullOrEmpty(config
                    .weightTransformationFunction)
                    ? loadFunction(config.weightTransformationFunction)
                    : null;
            if (!config.isSimpleWeights) {
                w = new short[elementCount];
                if (config.repetitions == 1) {
                    BinaryReader1D.readRowRange(config.weightMatrixFile,
                            globalThreadRowRange, ParallelOps.globalColCount,
                            byteOrder, true, function, w);
                } else {
                    BinaryReader1D.readRowRange(config.weightMatrixFile,
                            globalThreadRowRange, ParallelOps.globalColCount,
                            byteOrder, true, function, config.repetitions, w);
                }
                weights = new WeightsWrap1D(
                        w, distances, isSammon, ParallelOps.globalColCount);
            } else {
                double[] sw = null;
                sw = BinaryReader2D.readSimpleFile(config.weightMatrixFile,
                        config.numberDataPoints);
                weights = new WeightsWrap1D(sw, globalThreadRowRange,
                        distances, isSammon, ParallelOps.globalColCount,
                        function);
            }
        } else {
            weights = new WeightsWrap1D(
                    null, distances, isSammon, ParallelOps.globalColCount);
        }

    }

    private DoubleStatistics calculateStatisticsInternal(
            short[] distances, WeightsWrap1D weights, RefObj<Integer>
            refMissingDistCount) {

        int missingDistCount = 0;
        DoubleStatistics stat = new DoubleStatistics();

        int threadRowCount = ParallelOps.threadRowCounts[threadId];

        double origD, weight;
        for (int localRow = 0; localRow < threadRowCount; ++localRow) {
            for (int globalCol = 0; globalCol < ParallelOps.globalColCount;
                 globalCol++) {
                origD = distances[localRow * ParallelOps.globalColCount +
                        globalCol] * INV_SHORT_MAX;
                weight = weights.getWeight(localRow, globalCol);
                if (origD < 0) {
                    // Missing distance
                    ++missingDistCount;
                    continue;
                }
                if (weight == 0) continue; // Ignore zero weights

                stat.accept(origD);
            }
        }
        refMissingDistCount.setValue(missingDistCount);
        return stat;
    }


    /**
     * Parse command line arguments
     *
     * @param args Command line arguments
     * @param opts Command line options
     * @return An <code>Optional&lt;CommandLine&gt;</code> object
     */
    private static Optional<CommandLine> parseCommandLineArguments(
            String[] args, Options opts) {

        CommandLineParser optParser = new GnuParser();

        try {
            return Optional.fromNullable(optParser.parse(opts, args));
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return Optional.fromNullable(null);
    }

}
