/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.ucuenca.kodar.clusters;

import edu.uc.mahout.base.topicmodel.Cortical;
import edu.uc.mahout.base.topicmodel.SortMapperJob;
import edu.uc.mahout.base.topicmodel.Tagger;
import edu.ucuenca.kodar.utils.ExportFileClusterig;
import edu.ucuenca.kodar.utils.Writer;
import edu.ucuenca.kodar.utils.nlp.Category;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;
import net.didion.jwnl.JWNLException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.mahout.clustering.evaluation.ClusterEvaluator;
import org.apache.mahout.clustering.evaluation.RepresentativePointsDriver;
import org.apache.mahout.common.HadoopUtil;
import org.apache.mahout.common.distance.CosineDistanceMeasure;
import org.apache.mahout.common.distance.DistanceMeasure;

/**
 * Discover potential networks of collaboration and similar knowledge areas.
 *
 * @author Xavier Sumba <xavier.sumba93@ucuenca.ec>
 */
public class Clustering {

    private Date time;
    private String datasetPath;
    private boolean isCortical = false;
    private boolean isMahout = true;
    private boolean evaluate = false;
    private double interClusterDensityKmeans;
    private double interClusterDensityFkmeans;

    private final Controller controller;
    private final Writer writer = Writer.getWriteSequenceFile();
    private final ExportFileClusterig export = ExportFileClusterig.getExportFile();
    private Configuration conf = null;

    public static final File KODAR_HOME = setKodarVariable();
    public static final File RAW_DATA = new File(KODAR_HOME, "raw");
    public static final File SEQUENCE_DATA = new File(KODAR_HOME, "sequence");
    public static final File SPARSE_VECTORS = new File(KODAR_HOME, "sparse");
    public static final File KMEANS = new File(KODAR_HOME, "kmeans");
    public static final File FKMEANS = new File(KODAR_HOME, "fkmeans");
    public static final File EVALUATION = new File(KODAR_HOME, "evaluation");
    public static final File RESULT = new File(KODAR_HOME, "result");
    public static final File MR_JOBS = new File(KODAR_HOME, "mr_jobs");
    public static final File TOPMODEL = new File(KODAR_HOME, "topmodel");
    public static final File NAMED_CLUSTERS = new File(KODAR_HOME, "named_clusters");

    private static File setKodarVariable() {
        String env = System.getenv("KODAR_HOME");
        File dir;
        if (env == null) {
            env = System.getProperty("user.dir") + "/target/kodar_home";
        }

        dir = new File(env);
        if (!dir.exists()) {
            dir.mkdir();
        }

        return dir;
    }

    /**
     * The file to process should have the following headers.
     *
     * <code>name,authorUri,publicationUri,title,keywords</code>
     *
     * Results are stored in the KODAR HOME if provided otherwise are stored in
     * the project's folder.
     *
     * @param datasetPath Path of the file to process.
     */
    public Clustering(String datasetPath) {
        this.datasetPath = datasetPath;

        if (!RESULT.exists()) {
            RESULT.mkdir();
        }

        if (conf == null) {
            conf = new Configuration();
            //conf.set("fs.defaultFS", "hdfs://172.16.147.7:54310"); //set configuration of hadoop cluster 
        }
        controller = new ControllerImpl(conf);
    }

    public void run(int k) throws IOException, Exception {
        preprocessData();
        generateSparseVectors();
        executeKmeans(k);
        executeFuzzyKmeans();

        // To evaluate, we calculate k, we do not need labelling.
        if (evaluate) {
            evaluateCluster(conf);
            return;
        }
        writer.writeClusterVector(new Path(KMEANS.getPath(), "clusteredPoints/part-m-0"),
                new Path(KMEANS.getPath()));

        joinCLusterResults(KMEANS);
        joinCLusterResults(FKMEANS);

        labelCLusters();

        exportFiles();
    }

    private void preprocessData() throws IOException {
        // Separate keywords of remainning fields. 
        writer.disjoin(new File(datasetPath), RAW_DATA);

        /* The conversion of keywords to Sequence format is done twice because 
            there is the necessity of both files one of IntegerWritable type and 
            another one of Text type. */
        controller.rawToSequenceTextKey(new File(RAW_DATA, "keywords.csv"), new Path(SEQUENCE_DATA.getPath(), "output"), ",");
        controller.rawToSequenceLongKey(new File(RAW_DATA, "keywords.csv"), new Path(SEQUENCE_DATA.getPath(), "outputLong"), ",");

        // Conversion of remainning fields to SequenceFile format.
        controller.rawToSequenceLongKey(new File(RAW_DATA, "authors.csv"), new Path(SEQUENCE_DATA.getPath(), "outputAuthors"), ",");
    }

    private void generateSparseVectors() throws Exception {
        // Generate Sparse Vectors
        String[] seq2sparse = new String[]{
            "-i", new File(SEQUENCE_DATA, "output").getPath(),
            "-o", SPARSE_VECTORS.getPath(),
            "-x", "60",
            "-n", "2",
            "-ng", "2",
            "-wt", "tfidf",
            "-nv",
            "-ow"
        };

        controller.seq2Sparse(seq2sparse);
        writer.writeVector(new Path(SPARSE_VECTORS.getPath(), "tfidf-vectors/part-r-00000"), new Path(SPARSE_VECTORS.getPath()));
    }

    private void executeKmeans(int k) throws Exception {
        // Run KMeans
        String[] kmeans = new String[]{
            "-i", new File(SPARSE_VECTORS, "tfidf-vectors").getPath(),
            "-o", KMEANS.getPath(),
            "-c", new File(KMEANS, "seed").getPath(),
            "-dm", CosineDistanceMeasure.class.getName(),
            "-x", "100",
            "-k", String.valueOf(k),
            "-cl",
            "-xm", "sequential",
            "-ow"
        };
        controller.kmeans(kmeans);
    }

    private void executeFuzzyKmeans() throws Exception {
        String clustersKmeans = KMEANS.getPath() + "/" + getFinalPath(KMEANS.getPath());

        String[] fuzzykmeans = new String[]{
            "-i", new File(SPARSE_VECTORS, "tfidf-vectors").getPath(),
            "-c", clustersKmeans,
            "-o", FKMEANS.getPath(),
            "-dm", CosineDistanceMeasure.class.getName(),
            "-m", "1.8",
            "-x", "100",
            //"-k", "<optional number of initial clusters to sample from input vectors>",
            "-cd", "0.5",
            "-ow",
            "-cl",
            "-e",
            //"-t", "<threshold to use for clustering if -e is false>",
            "-xm", "sequential"
        };
        controller.fuzzyKmeans(fuzzykmeans);
    }

    private void joinCLusterResults(File cluster) throws IOException, ClassNotFoundException, InterruptedException, Exception {
        // Define directories paths.
        Path BASE_DIR = new Path(MR_JOBS.getPath(), cluster.getName());
        Path POINTS_TO_CLUSTERS = new Path(BASE_DIR, "points_to_clusters");
        Path CLUSTER_KEYWORDS = new Path(BASE_DIR, "clusteredKeywords");
        Path RESULT_OUTPUT = new Path(BASE_DIR, "clusteredData");
        Path SORT = new Path(BASE_DIR, "sort");

        // Delete everything in MR_JOBS.
        HadoopUtil.delete(conf, BASE_DIR);

        // Join point name with cluster id.
        PointToClusterMapperJob pointsToClusterMappingJob = new PointToClusterMapperJob(new Path(cluster.getPath(), "clusteredPoints"),
                POINTS_TO_CLUSTERS);
        pointsToClusterMappingJob.setConf(conf);
        pointsToClusterMappingJob.mapPointsToClusters();

        // Join keywords with pointsToClusters.
        ClusterJoinerMapperJob clusterJoinerJob = new ClusterJoinerMapperJob(new Path(SEQUENCE_DATA.getPath(), "outputLong"),
                POINTS_TO_CLUSTERS, CLUSTER_KEYWORDS);
        clusterJoinerJob.setConf(conf);
        clusterJoinerJob.run();

        // Join clusteredKeywords with authors.
        JoinKeywordsAuthorMapperJob joinKwAuthors = new JoinKeywordsAuthorMapperJob(CLUSTER_KEYWORDS,
                new Path(SEQUENCE_DATA.getPath(), "outputAuthors"), RESULT_OUTPUT);
        joinKwAuthors.setConf(conf);
        joinKwAuthors.run();

        // Sort and group.
        SortMapperJob sortByClusterId = new SortMapperJob(RESULT_OUTPUT, SORT);
        sortByClusterId.setConf(conf);
        sortByClusterId.run();
    }

    private void labelCLusters() throws IOException, JWNLException, Exception {
        String delimiter = "2db5c8", escapeContent = " Content: ", escapeAuthor = " Author:",
                escapeTitle = " Title: ";

        FileSystem fs = FileSystem.get(conf);
        Path TEMP = new Path(TOPMODEL.getPath(), "part000");
        File _documents = new File(TOPMODEL.getPath(), "documents");

        createDir(_documents, false);

        HadoopUtil.delete(conf, new Path(NAMED_CLUSTERS.getPath()));

        FileStatus[] folders = fs.listStatus(new Path(MR_JOBS.getPath()));

        for (FileStatus folder : folders) {
            FileStatus[] files = fs.listStatus(new Path(MR_JOBS.getPath() + "/" + folder.getPath().getName(), "sort"));
            for (FileStatus file : files) {
                // Ignore files like _SUCESS
                if (file.getPath().getName().startsWith("_")) {
                    continue;
                }

                SequenceFile.Reader reader = new SequenceFile.Reader(fs, file.getPath(), conf);
                LongWritable k = new LongWritable();
                Text v = new Text();
                String document = "", kws, title;

                Path fileNamedClusters = new Path(NAMED_CLUSTERS.getPath(), folder.getPath().getName());
                SequenceFile.Writer writeCluster = new SequenceFile.Writer(fs, conf, fileNamedClusters, Text.class, Text.class);

                int numDocs = 0;
                System.out.println("Reading: " + file.getPath());
                while (reader.next(k, v)) {

                    String[] values = v.toString().split(delimiter);
                    int id = 0;

                    SequenceFile.Writer writer = new SequenceFile.Writer(fs, conf, TEMP, Text.class, Text.class);
                    File _docs = new File(_documents, "docs" + numDocs);
                    createDir(_docs, false);

                    Cortical cortical = new Cortical();

                    for (String value : values) {
                        File _doc = new File(_docs, "doc" + id);
                        createDir(_doc, true);
                        kws = value.substring(value.indexOf(escapeContent) + escapeContent.length(),
                                value.indexOf(escapeAuthor));
                        title = value.substring(value.indexOf(escapeTitle) + escapeTitle.length());
                        Category c = new Category(kws);
                        c.populate();
                        document = title + "\n" + kws + "\n" + c.toString();
                        //document = kws;

                        // Execution with Cortical API
                        if (isCortical) {
                            cortical.addLabels(document);
                        } else if (isMahout) {
                            writer.append(new Text(String.valueOf(id)), new Text(document));
                        }

                        id++;
                        // Write documents in raw text
                        try (FileOutputStream out = new FileOutputStream(_doc)) {
                            out.write(document.getBytes());
                        }
                    }
                    writer.close();
                    Tagger tagger = new Tagger();
                    // Labelling using CVB algorithm.

                    if (isMahout) {
                        String label = tagger.tag(TEMP.toString());
                        File _label = new File(_docs, "label");
                        createDir(_label, true);
                        try (FileOutputStream out = new FileOutputStream(_label)) {
                            out.write(label.getBytes());
                        }
                        writeCluster.append(new Text(label), v);
                    } else if (isCortical) {
                        String labelCortical = cortical.getLabel();
                        File _labelCortical = new File(_docs, "labelCortical");
                        createDir(_labelCortical, true);
                        try (FileOutputStream out = new FileOutputStream(_labelCortical)) {
                            out.write(labelCortical.getBytes());
                        }
                        writeCluster.append(new Text(labelCortical), v);
                    }

                    HadoopUtil.delete(conf, TEMP);
                    numDocs++;
                }
                writeCluster.close();
            }
        }
    }

    private void exportFiles() throws IOException {
        FileSystem fs = FileSystem.get(conf);
        FileStatus[] files = fs.listStatus(new Path(NAMED_CLUSTERS.getPath()));

        for (FileStatus file : files) {

            String pathToExport = RESULT + "/" + file.getPath().getName();

            File directory = new File(pathToExport);
            if (!directory.exists()) {
                directory.mkdirs();
            }
            export.writeResultFileCSV(file.getPath().toString(), pathToExport + "/final.csv");
            export.writeResultFileJSON(file.getPath().toString(), pathToExport + "/final.json");
            export.writeResultFileRDF(file.getPath().toString(), pathToExport + "/final.nt");
        }
    }

    private void evaluateCluster(Configuration conf) throws InterruptedException, IOException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        HadoopUtil.delete(conf, new Path(EVALUATION.getPath()));

        DistanceMeasure measure = new CosineDistanceMeasure();
        int numIterations = 2;
        Path clustersIn = new Path(KMEANS.getPath(), "clusters-*-final/*");
        Path clusteredPointsIn = new Path(KMEANS.getPath(), "clusteredPoints");
        RepresentativePointsDriver.run(conf, clustersIn, clusteredPointsIn, new Path(EVALUATION.getPath()), measure,
                numIterations, false);
        //RepresentativePointsDriver.printRepresentativePoints(new Path(EVALUATION.getPath()), numIterations);
        // var clustersIn
        String clustersInStrKmeans = getFinalPath(KMEANS.getPath());
        String clustersInStrFkmeans = getFinalPath(FKMEANS.getPath());
        ClusterEvaluator evaluatorKmeans = new ClusterEvaluator(conf, new Path(KMEANS.getPath(), clustersInStrKmeans));
        ClusterEvaluator evaluatorFkmeans = new ClusterEvaluator(conf, new Path(FKMEANS.getPath(), clustersInStrFkmeans));
        interClusterDensityKmeans = evaluatorKmeans.interClusterDensity();
        interClusterDensityFkmeans = evaluatorFkmeans.interClusterDensity();

//        for (Map.Entry<Integer, Vector> entry : evaluator.interClusterDistances().entrySet()) {
//            System.out.println(entry.getKey());
//            System.out.println(entry.getValue());
//        }
//
//        System.out.println(evaluator.intraClusterDensities());
//        System.out.println(evaluator.interClusterDensity());
//        System.out.println(evaluator.intraClusterDensity());
//
//        Map<Integer, Vector> distances = evaluator.interClusterDistances();
//
//        for (Map.Entry<Integer, Vector> entry : distances.entrySet()) {
//            int key = entry.getKey();
//            Vector v = entry.getValue();
//
//            System.out.println("key=" + key);
//            System.out.println("value=" + v.asFormatString());
//        }
        //<editor-fold defaultstate="collapsed" desc="Read clustered Points, which contains the final mapping from clusterId to documentId">
//        FileSystem fs = FileSystem.get(conf);
//        SequenceFile.Reader reader = new SequenceFile.Reader(fs, new Path(clusteredPointsIn, "part-m-0"), conf);
//
//        IntWritable key = new IntWritable();
//        WeightedPropertyVectorWritable value = new WeightedPropertyVectorWritable();
//
//        while (reader.next(key, value)) {
//            NamedVector v = (NamedVector) value.getVector();
//            Iterator<Element> it = v.all().iterator();
//            System.out.println(v.getName());
//            System.out.print("[");
//            while (it.hasNext()) {
//                Element e = it.next();
//                System.out.print(e.get() + ",");
//            }
//            System.out.print("]\n");
//
//            Map<Text, Text> properties = value.getProperties();
//            for (Map.Entry<Text, Text> entry : properties.entrySet()) {
//                System.out.println("Key: " + entry.getKey().toString());
//                System.out.println("Value: " + entry.getValue().toString());
//            }
//            System.out.println(
//                    value.toString() + " belongs to cluster "
//                    + key.toString());
//        }
//</editor-fold>
    }

    private String getFinalPath(String path) {
        File folder = new File(path);
        String clustersInStr = "";
        for (String file : folder.list()) {
            if (file.contains("final")) {
                clustersInStr = file;
                break;
            }
        }
        return clustersInStr;
    }

    /**
     * Return the path of the dataset to been executed.
     *
     * @return
     */
    public String getDatasetPath() {
        return datasetPath;
    }

    /**
     * In case you want to change the path of the dataset to be executed.
     *
     * @param datasetPath
     */
    public void setDatasetPath(String datasetPath) {
        this.datasetPath = datasetPath;
    }

    /**
     * Return true if it is executing in evaluate mode.
     *
     * @return
     */
    public boolean isEvaluation() {
        return evaluate;
    }

    /**
     * Set <code>true</code> to execute en mode evaluate. Mode evaluate executes
     * a ClusterEvaluation to know the inter-cluster density.
     *
     * @param evaluation
     */
    public void setEvaluation(boolean evaluation) {
        this.evaluate = evaluation;
    }

    /**
     * Returns the Inter-cluster density for K-means algorithm. Inter-cluster
     * distance is a good measure of clustering quality; good clusters probably
     * don’t have centroids that are too close to each other, because this would
     * indicate that the clustering process is creating groups with similar
     * features, and perhaps drawing distinctions between cluster members that
     * are hard to support.
     *
     * Remember to set true the evaluate.
     *
     * @return
     */
    public double getInterClusterDensityKmeans() {
        return interClusterDensityKmeans;
    }

    /**
     * Returns the Inter-cluster density for Fuzzy K Means algorithm.
     * Inter-cluster distance is a good measure of clustering quality; good
     * clusters probably don’t have centroids that are too close to each other,
     * because this would indicate that the clustering process is creating
     * groups with similar features, and perhaps drawing distinctions between
     * cluster members that are hard to support.
     *
     * Remember to set true the evaluate.
     *
     * @return
     */
    public double getInterClusterDensityFuzzyKmeans() {
        return interClusterDensityFkmeans;
    }

    /**
     * Creates a directory or file.
     *
     * @param file
     * @param isFile true is @param file is a file.
     */
    private void createDir(File file, boolean isFile) throws IOException {
        if (isFile) {
            if (!file.exists()) {
                file.createNewFile();
            }
        } else if (!file.exists()) {
            file.mkdirs();
        }
    }

    /**
     * Return true it is executing the labeling work-flow with Cortical.
     *
     * @return the isCortical
     */
    public boolean isExecutingWithCortical() {
        return isCortical;
    }

    /**
     * Set to label each cortical using Cortical.
     *
     * @param isCortical the isCortical to set
     */
    public void executeWithCortical(boolean isCortical) {
        this.isCortical = isCortical;
        if (isCortical) {
            this.isMahout = false;
        } else {
            this.isMahout = true;
        }
    }

    /**
     * Return true it is executing the labeling work-flow with Apache Mahout.
     *
     * @return the isMahout
     */
    public boolean isExecutingWithMahout() {
        return isMahout;
    }

    /**
     * Set to label each cortical using Cortical. Executes by default with
     * Mahout.
     *
     * @param isMahout
     */
    public void executeWithMahoutl(boolean isMahout) {
        this.isMahout = isMahout;
        if (isMahout) {
            this.isCortical = false;
        } else {
            this.isCortical = true;
        }
    }

}
