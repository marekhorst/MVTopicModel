package org.madgik.MVTopicModel;

import cc.mallet.types.*;
import cc.mallet.pipe.*;
import cc.mallet.util.Maths;
import com.sree.textbytes.jtopia.Configuration;
import com.sree.textbytes.jtopia.TermDocument;
import com.sree.textbytes.jtopia.TermsExtractor;
import gnu.trove.map.hash.TObjectIntHashMap;

import java.util.*;
import java.io.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.apache.log4j.Logger;

import org.madgik.utils.CSV2FeatureSequence;
import org.madgik.utils.FeatureSequenceRemovePlural;
import static org.madgik.utils.Utils.cosineSimilarity;

public class PTMFlow {

    public enum ExperimentType {

        ACM,
        OpenAIRE,
        OAFETGrants,
        HEALTHTender,
        LFR,
        DBLP,
        DBLPNetOnly
    }

    public enum SimilarityType {

        cos,
        Jen_Sha_Div,
        symKL
    }

    public enum Net2BoWType {

        OneWay,
        TwoWay,
        PPR
    }

    public static Logger logger = Logger.getLogger(PTMFlow.class.getName());

    int topWords = 20;
    int showTopicsInterval = 50;
    byte numModalities = 6;
    double docTopicsThreshold = 0.03;
    int docTopicsMax = -1;
    int numOfThreads = 4;
    int numTopics = 400;
    int numIterations = 700; //Max 2000
    int numChars = 4000;
    int burnIn = 50;
    int optimizeInterval = 50;
    ExperimentType experimentType = ExperimentType.OpenAIRE;
    int pruneCnt = 300;
    int pruneLblCnt = 25;
    double pruneMaxPerc = 1;//Remove features that occur in more than (X*100)% of documents. 0.05 is equivalent to IDF of 3.0.
    double pruneMinPerc = 0.05;//Remove features that occur in more than (X*100)% of documents. 0.05 is equivalent to IDF of 3.0.
    boolean ACMAuthorSimilarity = true;
    boolean calcEntitySimilarities = false;
    boolean calcTopicSimilarities = false;
    boolean calcPPRSimilarities = false;
    boolean runTopicModelling = true;
    boolean runWordEmbeddings = false;
    boolean useTypeVectors = false;
    boolean trainTypeVectors = false;
    boolean findKeyPhrases = false;
    double useTypeVectorsProb = 0.6;
    Net2BoWType PPRenabled = Net2BoWType.OneWay;
    int vectorSize = 200;
    String SQLConnectionString = "jdbc:postgresql://localhost:5432/tender?user=postgres&password=postgres&ssl=false"; //"jdbc:sqlite:C:/projects/OpenAIRE/fundedarxiv.db";

    public PTMFlow() throws IOException {

        SimilarityType similarityType = SimilarityType.cos; //Cosine 1 jensenShannonDivergence 2 symmetric KLP

        String experimentId = experimentType.toString() + "_" + numTopics + "T_"
                + numIterations + "IT_" + numChars + "CHRs_" + pruneCnt + "_" + pruneLblCnt + "PRN" + burnIn + "B_" + numModalities + "M_" + numOfThreads + "TH_" + similarityType.toString() + (useTypeVectors ? "WV" : "") + PPRenabled.name();

        String experimentDescription = experimentId + ": \n";

        String dictDir = "";

        Connection connection = null;

        getPropValues();

        if (findKeyPhrases) {
            FindKeyPhrasesPerTopic(SQLConnectionString, experimentId, "openNLP");
        }

        if (runWordEmbeddings) {
            logger.info(" calc word embeddings starting");
            InstanceList[] instances = GenerateAlphabets(SQLConnectionString, experimentType, dictDir, numModalities, pruneCnt,
                    pruneLblCnt, pruneMaxPerc, pruneMinPerc, numChars, PPRenabled,
                    experimentType == ExperimentType.LFR || experimentType == ExperimentType.DBLP || experimentType == ExperimentType.DBLPNetOnly);
            logger.info(" instances added through pipe");

            //int numDimensions = 50;
            int windowSizeOption = 5;
            int numSamples = 5;
            WordEmbeddings matrix = new WordEmbeddings(instances[0].getDataAlphabet(), vectorSize, windowSizeOption);
            matrix.queryWord = "mining";
            matrix.countWords(instances[0]);
            matrix.train(instances[0], numOfThreads, numSamples);

            //PrintWriter out = new PrintWriter("vectors.txt");
            //matrix.write(out);
            //out.close();
            matrix.write(SQLConnectionString, 0);
            logger.info(" calc word embeddings ended");
        }

        if (runTopicModelling) {

            logger.info(" TopicModelling has started");
            String batchId = "-1";
            InstanceList[] instances = GenerateAlphabets(SQLConnectionString, experimentType, dictDir, numModalities, pruneCnt,
                    pruneLblCnt, pruneMaxPerc, pruneMinPerc, numChars, PPRenabled,
                    experimentType == ExperimentType.LFR || experimentType == ExperimentType.DBLP || experimentType == ExperimentType.DBLPNetOnly);
            logger.info("Instances added through pipe");

            double beta = 0.01;
            double[] betaMod = new double[numModalities];
            Arrays.fill(betaMod, 0.01);
            boolean useCycleProposals = false;
            double alpha = 0.1;

            double[] alphaSum = new double[numModalities];
            Arrays.fill(alphaSum, 1);

            double[] gamma = new double[numModalities];
            Arrays.fill(gamma, 1);

            //double gammaRoot = 4;
            FastQMVWVParallelTopicModel model = new FastQMVWVParallelTopicModel(numTopics, numModalities, alpha, beta, useCycleProposals, SQLConnectionString, useTypeVectors, useTypeVectorsProb, trainTypeVectors);

            model.CreateTables(SQLConnectionString, experimentId);

            // ParallelTopicModel model = new ParallelTopicModel(numTopics, 1.0, 0.01);
            model.setNumIterations(numIterations);
            model.setTopicDisplay(showTopicsInterval, topWords);
            // model.setIndependentIterations(independentIterations);
            model.optimizeInterval = optimizeInterval;
            model.burninPeriod = burnIn;
            model.setNumThreads(numOfThreads);

            model.addInstances(instances, batchId, vectorSize);//trainingInstances);//instances);
            logger.info(" instances added");

            //model.readWordVectorsDB(SQLConnectionString, vectorSize);
            model.estimate();
            logger.info("Model estimated");

            model.saveTopics(SQLConnectionString, experimentId, batchId);
            logger.info("Topics Saved");

            PrintWriter outState = null;// new PrintWriter(new FileWriter((new File(outputDocTopicsFile))));

            model.printDocumentTopics(outState, docTopicsThreshold, docTopicsMax, SQLConnectionString, experimentId, batchId);

            if (outState != null) {
                outState.close();
            }

            logger.info("printDocumentTopics finished");

            logger.info("Model Id: \n" + experimentId);

            logger.info("Model Metadata: \n" + model.getExpMetadata());

            //if (modelEvaluationFile != null) {
            try {

                double perplexity = 0;

                FastQMVWVTopicModelDiagnostics diagnostics = new FastQMVWVTopicModelDiagnostics(model, topWords);
                diagnostics.saveToDB(SQLConnectionString, experimentId, perplexity, batchId);
                logger.info("full diagnostics calculation finished");

            } catch (Exception e) {

                logger.error(e.getMessage());
            }

            experimentDescription = "Multi View Topic Modeling Analysis on ACM corpus";
            model.saveExperiment(SQLConnectionString, experimentId, experimentDescription);

            if (experimentType == ExperimentType.LFR) {
                FindGroundTruthCommunities(SQLConnectionString, experimentId);
            }

            logger.info("Insert default topic descriptions");

            try {

                String insertTopicDescriptionSql = "INSERT into TopicDescription (Title, Category, TopicId , VisibilityIndex, ExperimentId )\n"
                        + "select substr(string_agg(Item,','),1,100), '' , topicId , 1, '" + experimentId + "' \n"
                        + "from  TopicDescriptionView\n"
                        + " where experimentID = '" + experimentId + "' \n"
                        + " GROUP BY TopicID";

                connection = DriverManager.getConnection(SQLConnectionString);
                Statement statement = connection.createStatement();
                statement.setQueryTimeout(60);  // set timeout to 30 sec.
                statement.executeUpdate(insertTopicDescriptionSql);
                //ResultSet rs = statement.executeQuery(sql);

            } catch (SQLException e) {
                // if the error message is "out of memory", 
                // it probably means no database file is found
                logger.error(e.getMessage());
            } finally {
                try {
                    if (connection != null) {
                        connection.close();
                    }
                } catch (SQLException e) {
                    // connection close failed.
                    logger.error(e);
                }
            }
        }

        if (calcEntitySimilarities) {

            calcSimilaritiesAndTrends(SQLConnectionString, experimentType, experimentId, ACMAuthorSimilarity, similarityType, numTopics);

        }

        if (calcTopicSimilarities) {
            CalcTopicSimilarities(SQLConnectionString);
        }

        if (calcPPRSimilarities) {
            calcPPRSimilarities(SQLConnectionString);
        }

    }

    public void getPropValues() throws IOException {

        InputStream inputStream = null;
        try {
            Properties prop = new Properties();
            String propFileName = "config.properties";

            inputStream = getClass().getClassLoader().getResourceAsStream(propFileName);

            if (inputStream != null) {
                prop.load(inputStream);
            } else {
                throw new FileNotFoundException("property file '" + propFileName + "' not found in the classpath");
            }

            // get the property value and print it out
            numTopics = Integer.parseInt(prop.getProperty("TopicsNumber"));
            topWords = Integer.parseInt(prop.getProperty("TopWords"));
            numModalities = Byte.parseByte(prop.getProperty("NumModalities"));
            numIterations = Integer.parseInt(prop.getProperty("Iterations"));
            numOfThreads = Integer.parseInt(prop.getProperty("NumOfThreads"));
            numChars = Integer.parseInt(prop.getProperty("NumOfChars"));
            burnIn = Integer.parseInt(prop.getProperty("BurnIn"));
            optimizeInterval = Integer.parseInt(prop.getProperty("OptimizeInterval"));
            pruneCnt = Integer.parseInt(prop.getProperty("PruneCnt"));
            pruneLblCnt = Integer.parseInt(prop.getProperty("PruneLblCnt"));
            SQLConnectionString = prop.getProperty("SQLConnectionString");

        } catch (Exception e) {
            logger.error("Exception in reading properties: " + e);
        } finally {
            inputStream.close();
        }

    }

//    private void writeProperties() {
//        Properties prop = new Properties();
//        OutputStream output = null;
//
//        try {
//
//            String propFileName = "config.properties";
//
//            output = getClass().getClassLoader().getResourceAsStream(propFileName);
//
//            if (inputStream != null) {
//                prop.load(inputStream);
//            } else {
//                throw new FileNotFoundException("property file '" + propFileName + "' not found in the classpath");
//            }
//            
//            output = new FileOutputStream("config.properties");
//            
//            
//            // set the properties value
//            prop.setProperty("topWords", String.valueOf(topWords));
//            prop.setProperty("SQLConnectionString", SQLConnectionString);
//            prop.setProperty("showTopicsInterval", String.valueOf(showTopicsInterval));
//            prop.setProperty("numModalities", numModalities);
//            prop.setProperty("numOfThreads", numOfThreads);
//            prop.setProperty("numTopics", numTopics);
//            prop.setProperty("numIterations", numIterations);
//            prop.setProperty("numChars", numChars);
//            prop.setProperty("burnIn", burnIn);
//            prop.setProperty("optimizeInterval", optimizeInterval);
//            prop.setProperty("experimentType", experimentType);
//            prop.setProperty("pruneCnt", pruneCnt);
//            prop.setProperty("pruneLblCnt", pruneLblCnt);
//            prop.setProperty("pruneMaxPerc", pruneMaxPerc);
//            prop.setProperty("pruneMinPerc", pruneMinPerc);
//            prop.setProperty("calcEntitySimilarities", calcEntitySimilarities);
//            prop.setProperty("runTopicModelling", runTopicModelling);
//            prop.setProperty("findKeyPhrases", findKeyPhrases);
//            prop.setProperty("PPRenabled", PPRenabled);
//           
//
//            // save properties to project root folder
//            prop.store(output, null);
//
//        } catch (IOException io) {
//            io.printStackTrace();
//        } finally {
//            if (output != null) {
//                try {
//                    output.close();
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
//            }
//
//        }
//    }
    private void FindKeyPhrasesPerTopic(String SQLConnection, String experimentId, String tagger) {
        //for default lexicon POS tags
        //Configuration.setTaggerType("default"); 
        if (tagger == "openNLP") {
            // for openNLP POS tagger
            Configuration.setTaggerType(tagger);
            //for Stanford POS tagger
            // if tagger type is "openNLP" then give the openNLP POS tagger path
            Configuration.setModelFileLocation("model/openNLP/en-pos-maxent.bin");
        } else if (tagger == "stanford") {
            Configuration.setTaggerType("stanford");
            Configuration.setModelFileLocation("model/stanford/english-left3words-distsim.tagger");

        }

        Configuration.setSingleStrength(4);
        Configuration.setNoLimitStrength(2);
        // if tagger type is "default" then give the default POS lexicon file
        //Configuration.setModelFileLocation("model/default/english-lexicon.txt");
        // if tagger type is "stanford "
        //Configuration.setModelFileLocation("model/stanford/english-left3words-distsim.tagger");

        TermsExtractor termExtractor = new TermsExtractor();
        TermDocument topiaDoc = new TermDocument();

        StringBuffer stringBuffer = new StringBuffer();

        Connection connection = null;
        try {
            // create a database connection
            //connection = DriverManager.getConnection(SQLConnectionString);
            connection = DriverManager.getConnection(SQLConnection);
            Statement statement = connection.createStatement();
            statement.setQueryTimeout(30);  // set timeout to 30 sec.

            logger.info("Finding key phrases calculation started");

            String sql = "select pubtopic.TopicId, publication.title, publication.abstract from \n"
                    + "PubTopic\n"
                    + "inner join publication on PubTopic.pubId= publication.PubId and pubTOpic.Weight>0.55 \n"
                    + "where experimentId='" + experimentId + "' \n"
                    + "order by pubtopic.topicid, weight desc";

            ResultSet rs = statement.executeQuery(sql);

            HashMap<Integer, Map<String, ArrayList<Integer>>> topicTitles = null;

            topicTitles = new HashMap<Integer, Map<String, ArrayList<Integer>>>();

            Integer topicId = -1;

            while (rs.next()) {

                int newTopicId = rs.getInt("TopicId");

                if (newTopicId != topicId && topicId != -1) {
                    logger.info("Finding key phrases for topic " + topicId);
                    topiaDoc = termExtractor.extractTerms(stringBuffer.toString());
                    topicTitles.put(topicId, topiaDoc.getFinalFilteredTerms());
                    stringBuffer = new StringBuffer();
                }
                stringBuffer.append(rs.getString("title").replace('-', ' ').toLowerCase() + "\n");
                //stringBuffer.append(rs.getString("abstract").replace('-', ' ').toLowerCase() + "\n");
                topicId = newTopicId;

            }

            logger.info("Finding key phrases for topic " + topicId);
            topiaDoc = termExtractor.extractTerms(stringBuffer.toString());
            topicTitles.put(topicId, topiaDoc.getFinalFilteredTerms());

            statement.executeUpdate("create table if not exists TopicKeyPhrase ( TopicId Integer, Tagger TEXT, Phrase Text, Count Integer, WordsNum Integer, Weight double, ExperimentId TEXT) ");
            String deleteSQL = String.format("Delete from TopicKeyPhrase WHERE ExperimentId='" + experimentId + "' AND Tagger ='" + tagger + "'");
            statement.executeUpdate(deleteSQL);

            PreparedStatement bulkInsert = null;
            sql = "insert into TopicKeyPhrase values(?,?,?,?,?,?,?);";

            logger.info("Saving key phrases....");
            try {

                connection.setAutoCommit(false);
                bulkInsert = connection.prepareStatement(sql);

                for (Integer tmpTopicId : topicTitles.keySet()) {
                    //boolean startComparison = false;fuyhgjlkfdytrdfuikol
                    Map<String, ArrayList<Integer>> extractedPhrases = topicTitles.get(tmpTopicId);
                    for (String phrase : extractedPhrases.keySet()) {

                        bulkInsert.setInt(1, tmpTopicId);
                        bulkInsert.setString(2, tagger);
                        bulkInsert.setString(3, phrase);
                        bulkInsert.setInt(4, extractedPhrases.get(phrase).get(0));
                        bulkInsert.setInt(5, extractedPhrases.get(phrase).get(1));
                        bulkInsert.setDouble(6, 0);
                        bulkInsert.setString(7, experimentId);

                        bulkInsert.executeUpdate();
                    }

                }

                connection.commit();

            } catch (SQLException e) {

                logger.error("Error in insert topicPhrases: " + e);
                if (connection != null) {
                    try {
                        logger.error("Transaction is being rolled back");
                        connection.rollback();
                    } catch (SQLException excep) {
                        logger.error("Error in insert topicPhrases: " + excep);
                    }
                }
            } finally {

                if (bulkInsert != null) {
                    bulkInsert.close();
                }
                connection.setAutoCommit(true);
            }

        } catch (SQLException e) {
            // if the error message is "out of memory", 
            // it probably means no database file is found
            logger.error(e.getMessage());
        } finally {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (SQLException e) {
                // connection close failed.
                logger.error(e);
            }
        }

        logger.info("Finding Key phrases finished");

    }

    private void TfIdfWeighting(InstanceList instances, String SQLConnection, String experimentId, int itemType) {

        int N = instances.size();

        Alphabet alphabet = instances.getDataAlphabet();
        Object[] tokens = alphabet.toArray();
        System.out.println("# Number of dimensions: " + tokens.length);
        // determine document frequency for each term
        int[] df = new int[tokens.length];
        for (Instance instance : instances) {
            FeatureVector fv = new FeatureVector((FeatureSequence) instance.getData());
            int[] indices = fv.getIndices();
            for (int index : indices) {
                df[index]++;
            }
        }

        // determine document length for each document
        int[] lend = new int[N];
        double lenavg = 0;
        for (int i = 0; i < N; i++) {
            Instance instance = instances.get(i);
            FeatureVector fv = new FeatureVector((FeatureSequence) instance.getData());
            int[] indices = fv.getIndices();
            double length = 0.0;
            for (int index : indices) {
                length += fv.value(index);
            }
            lend[i] = (int) length;
            lenavg += length;
        }
        if (N > 1) {
            lenavg /= (double) N;
        }

        Connection connection = null;
        Statement statement = null;
        PreparedStatement bulkInsert = null;

        try {
            // create a database connection
            if (!SQLConnection.isEmpty()) {
                connection = DriverManager.getConnection(SQLConnection);
                statement = connection.createStatement();
                statement.setQueryTimeout(30);  // set timeout to 30 sec.
                statement.executeUpdate("create table if not exists TokensPerEntity (EntityId nvarchar(100), ItemType int, Token nvarchar(100), Counts double, TFIDFCounts double, ExperimentId nvarchar(50)) ");

                statement.executeUpdate("create Index if not exists IX_TokensPerEntity_Entity_Counts ON TokensPerEntity ( EntityId, ExperimentId, ItemType, Counts DESC, TFIDFCounts DESC, Token)");
                statement.executeUpdate("create Index if not exists IX_TokensPerEntity_Entity_TFIDFCounts ON TokensPerEntity ( EntityId, ExperimentId, ItemType,  TFIDFCounts DESC, Counts DESC, Token)");

                statement.executeUpdate("create View if not exists TokensPerEntityView AS select rv1.EntityId, rv1.ItemType, rv1.Token, rv1.Counts, rv1.TFIDFCounts, rv1.ExperimentId \n"
                        + "FROM TokensPerEntity rv1\n"
                        + "WHERE Token in\n"
                        + "(\n"
                        + "SELECT Token\n"
                        + "FROM TokensPerEntity rv2\n"
                        + "WHERE EntityId = rv1.EntityId AND Counts>2 AND ItemType=rv1.ItemType AND ExperimentId=rv1.ExperimentId \n"
                        + "ORDER BY\n"
                        + "TFIDFCounts DESC\n"
                        + "LIMIT 20\n"
                        + ")");

                String deleteSQL = String.format("Delete from TokensPerEntity where  ExperimentId = '%s' and itemtype= %d", experimentId, itemType);
                statement.executeUpdate(deleteSQL);

                String sql = "insert into TokensPerEntity values(?,?,?,?,?,?);";

                connection.setAutoCommit(false);
                bulkInsert = connection.prepareStatement(sql);

                for (int i = 0; i < N; i++) {
                    Instance instance = instances.get(i);

                    FeatureVector fv = new FeatureVector((FeatureSequence) instance.getData());
                    int[] indices = fv.getIndices();
                    for (int index : indices) {
                        double tf = fv.value(index);
                        double tfcomp = tf / (tf + 0.5 + 1.5 * (double) lend[i] / lenavg);
                        double idfcomp = Math.log((double) N / (double) df[index]) / Math.log(N + 1);
                        double tfIdf = tfcomp * idfcomp;
                        fv.setValue(index, tfIdf);
                        String token = fv.getAlphabet().lookupObject(index).toString();

                        bulkInsert.setString(1, instance.getName().toString());
                        bulkInsert.setInt(2, itemType);
                        bulkInsert.setString(3, token);
                        bulkInsert.setDouble(4, tf);
                        bulkInsert.setDouble(5, tfIdf);
                        bulkInsert.setString(6, experimentId);

                        bulkInsert.executeUpdate();
                    }
                }

                connection.commit();
            }
        } catch (SQLException e) {

            if (connection != null) {
                try {
                    logger.error("Transaction is being rolled back");
                    connection.rollback();
                } catch (SQLException excep) {
                    logger.error("Error in insert TokensPerEntity");
                }
            }
        } finally {
            try {
                if (bulkInsert != null) {
                    bulkInsert.close();
                }
                connection.setAutoCommit(true);
            } catch (SQLException excep) {
                logger.error("Error in insert TokensPerEntity");
            }
        }

        //TODO: Sort Feature Vector Values
        // FeatureVector.toSimpFilefff
    }

    private void GenerateStoplist(SimpleTokenizer prunedTokenizer, ArrayList<Instance> instanceBuffer, int pruneCount, double docProportionMinCutoff, double docProportionMaxCutoff, boolean preserveCase)
            throws IOException {

        //SimpleTokenizer st = new SimpleTokenizer(new File("stoplists/en.txt"));
        ArrayList<Instance> input = new ArrayList<Instance>();
        for (Instance instance : instanceBuffer) {
            input.add((Instance) instance.clone());
        }

        ArrayList<Pipe> pipes = new ArrayList<Pipe>();
        Alphabet alphabet = new Alphabet();

        CharSequenceLowercase csl = new CharSequenceLowercase();
        //prunedTokenizer = st.deepClone();
        SimpleTokenizer st = prunedTokenizer.deepClone();
        StringList2FeatureSequence sl2fs = new StringList2FeatureSequence(alphabet);
        FeatureCountPipe featureCounter = new FeatureCountPipe(alphabet, null);
        FeatureDocFreqPipe docCounter = new FeatureDocFreqPipe(alphabet, null);

        pipes.add(new Input2CharSequence()); //homer

        if (!preserveCase) {
            pipes.add(csl);
        }
        pipes.add(st);
        pipes.add(sl2fs);
        if (pruneCount > 0) {
            pipes.add(featureCounter);
        }
        if (docProportionMaxCutoff < 1.0) {
            //if (docProportionMaxCutoff < 1.0 || docProportionMinCutoff > 0) {
            pipes.add(docCounter);
        }

        Pipe serialPipe = new SerialPipes(pipes);
        Iterator<Instance> iterator = serialPipe.newIteratorFrom(input.iterator());

        int count = 0;

        // We aren't really interested in the instance itself,
        //  just the total feature counts.
        while (iterator.hasNext()) {
            count++;
            if (count % 100000 == 0) {
                System.out.println(count);
            }
            iterator.next();
        }

        Iterator<String> wordIter = alphabet.iterator();
        while (wordIter.hasNext()) {
            String word = (String) wordIter.next();

            if (!word.matches("^(?!.*(-[^-]*-|_[^_]*_))[A-Za-z0-9][\\w-]*[A-Za-z0-9]$") || word.contains("cid") || word.contains("italic") || word.contains("null") || word.contains("usepackage") || word.contains("fig")) {
                prunedTokenizer.stop(word);
            }
        }

        prunedTokenizer.stop("tion");
        prunedTokenizer.stop("ing");
        prunedTokenizer.stop("ment");
        prunedTokenizer.stop("ytem");
        prunedTokenizer.stop("wth");
        prunedTokenizer.stop("whch");
        prunedTokenizer.stop("nfrmatn");
        prunedTokenizer.stop("uer");
        prunedTokenizer.stop("ther");
        prunedTokenizer.stop("frm");
        prunedTokenizer.stop("hypermeda");
        prunedTokenizer.stop("anuae");
        prunedTokenizer.stop("dcument");
        prunedTokenizer.stop("tudent");
        prunedTokenizer.stop("appcatn");
        prunedTokenizer.stop("tructure");
        prunedTokenizer.stop("prram");
        prunedTokenizer.stop("den");
        prunedTokenizer.stop("aed");
        prunedTokenizer.stop("cmputer");
        prunedTokenizer.stop("prram");

        prunedTokenizer.stop("mre");
        prunedTokenizer.stop("cence");
        prunedTokenizer.stop("tures");
        prunedTokenizer.stop("ture");
        prunedTokenizer.stop("ments");
        prunedTokenizer.stop("cations");
        prunedTokenizer.stop("tems");
        prunedTokenizer.stop("tem");
        prunedTokenizer.stop("tional");
        prunedTokenizer.stop("ity");
        prunedTokenizer.stop("ware");
        prunedTokenizer.stop("opment");
        prunedTokenizer.stop("guage");
        prunedTokenizer.stop("niques");

        if (pruneCount > 0) {
            featureCounter.addPrunedWordsToStoplist(prunedTokenizer, pruneCount);
        }
        if (docProportionMaxCutoff < 1.0) {
            docCounter.addPrunedWordsToStoplist(prunedTokenizer, docProportionMaxCutoff);
        }

//        if (pruneCount > 0) {
//            featureCounter.addPrunedWordsToStoplist(prunedTokenizer, pruneCount);
//        }
//        if (docProportionMaxCutoff < 1.0 || docProportionMinCutoff > 0) {
//            docCounter.addPrunedWordsToStoplist(prunedTokenizer, docProportionMaxCutoff, docProportionMinCutoff);
//        }
    }

    public void createCitationGraphFile(String outputCsv, String SQLLitedb) {
        //String SQLConnectionString = "jdbc:sqlite:C:/projects/OpenAIRE/fundedarxiv.db";

        Connection connection = null;
        try {

            FileWriter fwrite = new FileWriter(outputCsv);
            BufferedWriter out = new BufferedWriter(fwrite);
            String header = "# DBLP citation graph \n"
                    + "# fromNodeId, toNodeId \n";
            out.write(header);

            connection = DriverManager.getConnection(SQLLitedb);

            String sql = "select id, ref_id from papers where ref_num >0 ";
            Statement statement = connection.createStatement();
            statement.setQueryTimeout(30);  // set timeout to 30 sec.
            ResultSet rs = statement.executeQuery(sql);
            while (rs.next()) {
                // read the result set
                int Id = rs.getInt("Id");
                String citationNums = rs.getString("ref_id");

                String csvLine = "";//Id + "\t" + citationNums;

                String[] str = citationNums.split("\t");
                for (int i = 0; i < str.length - 1; i++) {
                    csvLine = Id + "\t" + str[i];
                    out.write(csvLine + "\n");
                }

            }
            out.flush();
        } catch (SQLException e) {
            // if the error message is "out of memory", 
            // it probably means no database file is found
            logger.error(e.getMessage());
        } catch (Exception e) {
            logger.error("File input error");
        } finally {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (SQLException e) {
                // connection close failed.
                logger.error(e);
            }
        }

    }

    private class TopicVector {

        public int TopicId;
        public String ExperimentId;
        public double[] Vector;
    }

    public boolean isAlphanumeric(String str) {

        return str.matches("^(?!.*(-[^-]*-|_[^_]*_))[A-Za-z0-9][\\w-]*[A-Za-z0-9]$");
        //Pattern p = Pattern.compile("[^a-zA-Z0-9]+$");

    }

    public void CalcTopicSimilarities(String SQLLitedb) {

        Connection connection = null;
        try {

            connection = DriverManager.getConnection(SQLLitedb);
            Statement statement = connection.createStatement();
            statement.setQueryTimeout(30);  // set timeout to 30 sec.

            logger.info("Calc topic similarities started");

            String distinctTopicsSQL = "Select  TopicId,  ExperimentId, count(*) as cnt\n"
                    + "from TopicVector\n  "
                    + "group by TopicId,  ExperimentId";

            ResultSet rs = statement.executeQuery(distinctTopicsSQL);

            List<TopicVector> topicVectors = new ArrayList<TopicVector>();

            while (rs.next()) {

                TopicVector topicVector = new TopicVector();

                topicVector.ExperimentId = rs.getString("ExperimentId");
                topicVector.TopicId = rs.getInt("TopicId");
                //String newLabelId = experimentId + "_" + topicId;
                int dimension = rs.getInt("cnt");
                topicVector.Vector = new double[dimension];

                String selectVectorSQL = String.format("Select Weight from topicVector where ExperimentId= '%s'  and TopicId=%d order by ColumnId", topicVector.ExperimentId, topicVector.TopicId);

                Statement statement2 = connection.createStatement();
                statement2.setQueryTimeout(30);  // set timeout to 30 sec.
                ResultSet rs1 = statement2.executeQuery(selectVectorSQL);
                int cnt = 0;
                while (rs1.next()) {
                    topicVector.Vector[cnt++] = rs1.getDouble("Weight");
                }

                topicVectors.add(topicVector);

            }

            double similarity = 0;
            double similarityThreshold = 0.3;

            statement.executeUpdate("create table if not exists TopicSimilarity (ExperimentId1 TEXT, TopicId1 TEXT, ExperimentId2 TEXT, TopicId2 TEXT, Similarity double) ");
            String deleteSQL = String.format("Delete from TopicSimilarity");
            statement.executeUpdate(deleteSQL);

            PreparedStatement bulkInsert = null;
            String insertSql = "insert into TopicSimilarity values(?,?,?,?,?);";

            try {

                connection.setAutoCommit(false);
                bulkInsert = connection.prepareStatement(insertSql);

                for (int t1 = 0; t1 < topicVectors.size(); t1++) {
                    for (int t2 = t1; t2 < topicVectors.size(); t2++) {

                        similarity = Math.max(cosineSimilarity(topicVectors.get(t1).Vector, topicVectors.get(t2).Vector), 0);

                        if (similarity > similarityThreshold && !(topicVectors.get(t1).TopicId == topicVectors.get(t2).TopicId && topicVectors.get(t1).ExperimentId == topicVectors.get(t2).ExperimentId)) {

                            bulkInsert.setString(1, topicVectors.get(t1).ExperimentId);
                            bulkInsert.setInt(2, topicVectors.get(t1).TopicId);
                            bulkInsert.setString(3, topicVectors.get(t2).ExperimentId);
                            bulkInsert.setInt(4, topicVectors.get(t2).TopicId);
                            bulkInsert.setDouble(5, (double) Math.round(similarity * 1000) / 1000);

                            bulkInsert.executeUpdate();
                        }
                    }
                }

                connection.commit();

            } catch (SQLException e) {

                if (connection != null) {
                    try {
                        logger.error("Transaction is being rolled back");
                        connection.rollback();
                    } catch (SQLException excep) {
                        logger.error("Error in insert grantSimilarity");
                    }
                }
            } finally {

                if (bulkInsert != null) {
                    bulkInsert.close();
                }
                connection.setAutoCommit(true);
            }

        } catch (SQLException e) {
            // if the error message is "out of memory", 
            // it probably means no database file is found
            logger.error(e.getMessage());
        } finally {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (SQLException e) {
                // connection close failed.
                logger.error(e);
            }
        }

        logger.info("Topic similarities calculation finished");

    }

    public void calcPPRSimilarities(String SQLLitedb) {
        //calc similarities

        //logger.info("PPRSimilarities calculation Started");
        Connection connection = null;
        try {
            // create a database connection
            //connection = DriverManager.getConnection(SQLConnectionString);
            connection = DriverManager.getConnection(SQLLitedb);
            Statement statement = connection.createStatement();
            statement.setQueryTimeout(30);  // set timeout to 30 sec.

            logger.info("PPRSimilarities calculation Started");

            String sql = "SELECT source.OrigId||'PPR' AS PubID, target.OrigId  AS CitationId, prLinks.Counts As Counts FROM prLinks\n"
                    + "INNER JOIN PubCitationPPRAlias source ON source.RowId = PrLinks.Source\n"
                    + "INNER JOIN PubCitationPPRAlias target ON target.RowId = PrLinks.Target\n"
                    + "Union\n"
                    + "Select PubId, CitationId, 1 as Counts From PubCitation\n"
                    + "ORDER by PubId ";

            ResultSet rs = statement.executeQuery(sql);

            HashMap<String, SparseVector> labelVectors = null;
            //HashMap<String, double[]> similarityVectors = null;
            labelVectors = new HashMap<String, SparseVector>();

            String labelId = "";

            int[] citations = new int[350];
            double[] weights = new double[350];
            int cnt = 0;

            while (rs.next()) {

                String newLabelId = "";
                newLabelId = rs.getString("PubId");
                if (!newLabelId.equals(labelId) && !labelId.isEmpty()) {
                    labelVectors.put(labelId, new SparseVector(citations, weights, citations.length, citations.length, true, true, true));
                    citations = new int[350];
                    weights = new double[350];
                    cnt = 0;
                }
                labelId = newLabelId;
                citations[cnt] = rs.getInt("CitationId");
                weights[cnt] = rs.getDouble("Counts");
                cnt++;

            }

            cnt = 0;
            double similarity = 0;

            NormalizedDotProductMetric cosineSimilarity = new NormalizedDotProductMetric();

            statement.executeUpdate("create table if not exists PPRPubCitationSimilarity (PubId TEXT,  Similarity double) ");
            String deleteSQL = String.format("Delete from PPRPubCitationSimilarity");
            statement.executeUpdate(deleteSQL);

            PreparedStatement bulkInsert = null;
            sql = "insert into PPRPubCitationSimilarity values(?,?);";

            try {

                connection.setAutoCommit(false);
                bulkInsert = connection.prepareStatement(sql);

                for (String fromPubId : labelVectors.keySet()) {

                    if (fromPubId.contains("PPR")) {
                        continue;
                    }
                    String toPubId = fromPubId + "PPR";
                    similarity = -1;

                    if (labelVectors.get(fromPubId) != null && labelVectors.get(toPubId) != null) {
                        similarity = 1 - Math.abs(cosineSimilarity.distance(labelVectors.get(fromPubId), labelVectors.get(toPubId))); // the function returns distance not similarity
                    }
                    bulkInsert.setString(1, fromPubId);
                    bulkInsert.setDouble(2, (double) Math.round(similarity * 1000) / 1000);

                    bulkInsert.executeUpdate();

                }

                connection.commit();

            } catch (SQLException e) {

                if (connection != null) {
                    try {
                        logger.error("Transaction is being rolled back");
                        connection.rollback();
                    } catch (SQLException excep) {
                        logger.error("Error in insert grantSimilarity");
                    }
                }
            } finally {

                if (bulkInsert != null) {
                    bulkInsert.close();
                }
                connection.setAutoCommit(true);
            }

        } catch (SQLException e) {
            // if the error message is "out of memory", 
            // it probably means no database file is found
            logger.error(e.getMessage());
        } finally {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (SQLException e) {
                // connection close failed.
                logger.error(e);
            }
        }

        logger.info("Pub citation similarities calculation finished");
    }

    public void FindGroundTruthCommunities(String SQLLitedb, String experimentId) {
        //calc similarities

        //logger.info("PPRSimilarities calculation Started");
        Connection connection = null;
        try {
            // create a database connection
            //connection = DriverManager.getConnection(SQLConnectionString);
            connection = DriverManager.getConnection(SQLLitedb);
            Statement statement = connection.createStatement();
            statement.setQueryTimeout(30);  // set timeout to 30 sec.

            logger.info("PPRSimilarities calculation Started");

            String sql = "select TopicId||'EXP' as GroupId, PubId as NodeId from PubTopic\n"
                    + "WHERE ExperimentId = '" + experimentId + "'\n"
                    + "UNION \n"
                    + "Select  GroupId,NodeId from GroundTruth\n"
                    + "Order By GroupId";

            ResultSet rs = statement.executeQuery(sql);

            HashMap<String, SparseVector> labelVectors = null;
            //HashMap<String, double[]> similarityVectors = null;
            labelVectors = new HashMap<String, SparseVector>();

            String labelId = "";

            int[] citations = new int[30000];
            double[] weights = new double[30000];
            int cnt = 0;

            while (rs.next()) {

                String newLabelId = "";
                newLabelId = rs.getString("GroupId");
                if (!newLabelId.equals(labelId) && !labelId.isEmpty()) {
                    labelVectors.put(labelId, new SparseVector(citations, weights, citations.length, citations.length, true, true, true));
                    citations = new int[30000];
                    weights = new double[30000];
                    cnt = 0;
                }
                labelId = newLabelId;
                citations[cnt] = rs.getInt("NodeId");
                weights[cnt] = 1; // rs.getDouble("Counts");
                cnt++;

            }

            cnt = 0;
            double similarity = 0;

            NormalizedDotProductMetric cosineSimilarity = new NormalizedDotProductMetric();

            statement.executeUpdate("create table if not exists GroundTruth2TopicId (Community TEXT,  TopicId TEXT, Similarity double, ExperimentId TEXT) ");
            String deleteSQL = String.format("Delete from GroundTruth2TopicId WHERE ExperimentId='" + experimentId + "'");
            statement.executeUpdate(deleteSQL);

            PreparedStatement bulkInsert = null;
            sql = "insert into GroundTruth2TopicId values(?,?,?,?);";

            try {

                connection.setAutoCommit(false);
                bulkInsert = connection.prepareStatement(sql);

                for (String fromPubId : labelVectors.keySet()) {
                    //boolean startComparison = false;
                    for (String toPubId : labelVectors.keySet()) {

                        if (fromPubId.contains("EXP") || !toPubId.contains("EXP")) {
                            //if (! startComparison) {
                            //      startComparison = (fromPubId == toPubId );
                            continue;
                        }
                        //String toPubId = fromPubId + "EXP";
                        similarity = -1;

                        if (labelVectors.get(fromPubId) != null && labelVectors.get(toPubId) != null) {
                            similarity = 1 - Math.abs(cosineSimilarity.distance(labelVectors.get(fromPubId), labelVectors.get(toPubId))); // the function returns distance not similarity
                        }
                        if (similarity > 0.4) {
                            bulkInsert.setString(1, fromPubId);
                            bulkInsert.setString(2, toPubId);
                            bulkInsert.setDouble(3, (double) Math.round(similarity * 1000) / 1000);
                            bulkInsert.setString(4, experimentId);
                            bulkInsert.executeUpdate();
                        }

                    }
                }

                connection.commit();

            } catch (SQLException e) {

                if (connection != null) {
                    try {
                        logger.error("Transaction is being rolled back");
                        connection.rollback();
                    } catch (SQLException excep) {
                        logger.error("Error in insert grantSimilarity");
                    }
                }
            } finally {

                if (bulkInsert != null) {
                    bulkInsert.close();
                }
                connection.setAutoCommit(true);
            }

        } catch (SQLException e) {
            // if the error message is "out of memory", 
            // it probably means no database file is found
            logger.error(e.getMessage());
        } finally {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (SQLException e) {
                // connection close failed.
                logger.error(e);
            }
        }

        logger.info("Pub citation similarities calculation finished");
    }

    public void calcSimilaritiesAndTrends(String SQLLitedb, ExperimentType experimentType, String experimentId, boolean ACMAuthorSimilarity, SimilarityType similarityType, int numTopics) {
        //calc similarities

        logger.info("similarities calculation Started");
        Connection connection = null;
        try {
            // create a database connection
            //connection = DriverManager.getConnection(SQLConnectionString);
            connection = DriverManager.getConnection(SQLLitedb);
            Statement statement = connection.createStatement();
            statement.setQueryTimeout(30);  // set timeout to 30 sec.

            logger.info("similarities calculation Started");

            String sql = "";
            switch (experimentType) {
//                case Grants:
//                    sql = "select    GrantId, TopicId, AVG(weight) as Weight from PubTopic Inner Join GrantPerDoc on PubTopic.PubId= GrantPerDoc.DocId"
//                            + " where weight>0.02 AND ExperimentId='" + experimentId + "' group By GrantId , TopicId order by  GrantId   , TopicId";
//                    break;
//                case FullGrants:
//                    sql = "select    project_code, TopicId, AVG(weight) as Weight from PubTopic Inner Join links  on PubTopic.PubId= links.OriginalId "
//                            + " where weight>0.02 AND ExperimentId='" + experimentId
//                            + "' group By project_code , TopicId order by  project_code, TopicId";
//
//                    break;
//                case FETGrants:
//                    sql = "select    project_code, TopicId, AVG(weight) as Weight from PubTopic Inner Join links  on PubTopic.PubId= links.OriginalId "
//                            + "   inner join projectView on links.project_code=projectView.GrantId and links.funder='FP7'  and Category1<>'NONFET'\n"
//                            + " where weight>0.02 AND ExperimentId='" + experimentId
//                            + "' group By project_code , TopicId order by  project_code, TopicId";
//
//                    break;
                case OpenAIRE:
                case HEALTHTender:
                    sql = "select EntityTopicDistribution.EntityId as projectId, EntityTopicDistribution.TopicId, EntityTopicDistribution.NormWeight as Weight \n"
                            + "                            from EntityTopicDistribution\n"
                            + "                            where EntityTopicDistribution.EntityType='Grant' AND EntityTopicDistribution.EntityId<>'' AND\n"
                            + "                            EntityTopicDistribution.experimentId= '" + experimentId + "'   and EntityTopicDistribution.NormWeight>0.03\n"
                            + "                            and EntityTopicDistribution.EntityId in (Select grantId FROM PubGrant GROUP BY grantId HAVING Count(*)>4)\n"
                            + "                            and EntityTopicDistribution.topicid in (select TopicId from topicdescription \n"
                            + "                            where topicdescription.experimentId='" + experimentId + "' and topicdescription.VisibilityIndex>1)";

                    break;
//                case Authors:
//                    sql = "select    AuthorId, TopicId, AVG(weight) as Weight from PubTopic Inner Join AuthorPerDoc on PubTopic.PubId= AuthorPerDoc.DocId"
//                            + " where weight>0.02 AND ExperimentId='" + experimentId + "' group By AuthorId , TopicId order by  AuthorId   , TopicId";
//                    break;
                case ACM:
                    if (ACMAuthorSimilarity) {
                        sql = "select EntityTopicDistribution.EntityId as authorId, EntityTopicDistribution.TopicId, EntityTopicDistribution.NormWeight as Weight \n"
                                + "                            from EntityTopicDistribution\n"
                                + "                            where EntityTopicDistribution.EntityType='Author' AND EntityTopicDistribution.EntityId<>'' AND\n"
                                + "                            EntityTopicDistribution.experimentId= '" + experimentId + "'   and EntityTopicDistribution.NormWeight>0.03\n"
                                + "                            and EntityTopicDistribution.EntityId in (Select AuthorId FROM PubAuthor GROUP BY AuthorId HAVING Count(*)>4)\n"
                                + "                            and EntityTopicDistribution.topicid in (select TopicId from topicdescription \n"
                                + "                            where topicdescription.experimentId='" + experimentId + "' and topicdescription.VisibilityIndex>0)";

//                        sql = "select TopicDistributionPerAuthorView.AuthorId, TopicDistributionPerAuthorView.TopicId, TopicDistributionPerAuthorView.NormWeight as Weight \n"
//                                + "from TopicDistributionPerAuthorView\n"
//                                + "where TopicDistributionPerAuthorView.experimentId='" + experimentId + "'   and TopicDistributionPerAuthorView.NormWeight>0.03\n"
//                                + "and TopicDistributionPerAuthorView.AuthorId in (Select AuthorId FROM PubAuthor GROUP BY AuthorId HAVING Count(*)>4)\n"
//                                + "and TopicDistributionPerAuthorView.topicid in (select TopicId from topicdescription \n"
//                                + "where topicdescription.experimentId='" + experimentId + "' and topicdescription.VisibilityIndex>1)";
                    } else {
                        sql = "select EntityTopicDistribution.EntityId as VenueId, EntityTopicDistribution.TopicId  as TopicId, EntityTopicDistribution.NormWeight as Weight \n"
                                + "                                                          from EntityTopicDistribution\n"
                                + "                                                          where EntityTopicDistribution.EntityType='Journal' AND EntityTopicDistribution.EntityId<>'' AND\n"
                                + "                                                          EntityTopicDistribution.experimentId= '" + experimentId + "'   and EntityTopicDistribution.NormWeight>0.03\n"
                                + "                                                          and EntityTopicDistribution.EntityId in (Select ISSN FROM PubJournal GROUP BY ISSN HAVING Count(*)>100)\n"
                                + "                                                          and EntityTopicDistribution.topicid in (select TopicId from topicdescription \n"
                                + "                                                          where topicdescription.experimentId='" + experimentId + "' and topicdescription.VisibilityIndex=1)\n"
                                + "          UNION                                                \n"
                                + "select EntityTopicDistribution.EntityId as VenueId, EntityTopicDistribution.TopicId as TopicId, EntityTopicDistribution.NormWeight as Weight \n"
                                + "                                                          from EntityTopicDistribution\n"
                                + "                                                          where EntityTopicDistribution.EntityType='Conference' AND EntityTopicDistribution.EntityId<>'' AND\n"
                                + "                                                          EntityTopicDistribution.experimentId= '" + experimentId + "'   and EntityTopicDistribution.NormWeight>0.03\n"
                                + "                                                          and EntityTopicDistribution.EntityId in (Select SeriesId FROM PubConference GROUP BY SeriesId HAVING Count(*)>400)\n"
                                + "                                                          and EntityTopicDistribution.topicid in (select TopicId from topicdescription \n"
                                + "                                                          where topicdescription.experimentId='" + experimentId + "' and topicdescription.VisibilityIndex=1)";
                    }

                    break;
//                case PM_pdb:
//                    sql = "select    pdbCode, TopicId, AVG(weight) as Weight from topicsPerDoc Inner Join pdblink on topicsPerDoc.DocId= pdblink.pmcId"
//                            + " where weight>0.02 AND ExperimentId='" + experimentId + "' group By pdbCode , TopicId order by  pdbCode   , TopicId";
//
//                    break;
//                case DBLP:
//                    sql = "select  Source, TopicId, AVG(weight) as Weight from PubTopic Inner Join prlinks on PubTopic.PubId= prlinks.source"
//                            + " where weight>0.02 AND ExperimentId='" + experimentId + "' group By Source , TopicId order by  Source   , TopicId";
//
//                    break;
                default:
            }

            // String sql = "select fundedarxiv.file from fundedarxiv inner join funds on file=filename Group By fundedarxiv.file LIMIT 10" ;
            ResultSet rs = statement.executeQuery(sql);

            HashMap<String, SparseVector> labelVectors = null;
            HashMap<String, double[]> similarityVectors = null;
            if (similarityType == SimilarityType.cos) {
                labelVectors = new HashMap<String, SparseVector>();
            } else {
                similarityVectors = new HashMap<String, double[]>();
            }

            String labelId = "";
            int[] topics = new int[numTopics];
            double[] weights = new double[numTopics];
            int cnt = 0;
            double a;
            while (rs.next()) {

                String newLabelId = "";

                switch (experimentType) {

                    case OpenAIRE:
                    case OAFETGrants:
                    case HEALTHTender:
                        newLabelId = rs.getString("projectId");
                        break;

                    case ACM:
                        if (ACMAuthorSimilarity) {
                            newLabelId = rs.getString("AuthorId");
                        } else {
                            newLabelId = rs.getString("VenueId");
                        }
                        break;

                    default:
                }

                if (!newLabelId.equals(labelId) && !labelId.isEmpty()) {
                    if (similarityType == SimilarityType.cos) {
                        labelVectors.put(labelId, new SparseVector(topics, weights, topics.length, topics.length, true, true, true));
                    } else {
                        similarityVectors.put(labelId, weights);
                    }
                    topics = new int[numTopics];
                    weights = new double[numTopics];
                    cnt = 0;
                }
                labelId = newLabelId;
                topics[cnt] = rs.getInt("TopicId");
                weights[cnt] = rs.getDouble("Weight");
                cnt++;

            }

            cnt = 0;
            double similarity = 0;
            double similarityThreshold = 0.15;
            NormalizedDotProductMetric cosineSimilarity = new NormalizedDotProductMetric();

            int entityType = experimentType.ordinal();
            if (experimentType == ExperimentType.ACM && !ACMAuthorSimilarity) {
                entityType = 100 + entityType;
            };

            statement.executeUpdate("create table if not exists EntitySimilarity (EntityType int, EntityId1 nvarchar(50), EntityId2 nvarchar(50), Similarity double, ExperimentId nvarchar(50)) ");
            String deleteSQL = String.format("Delete from EntitySimilarity where  ExperimentId = '%s' and entityType=%d", experimentId, entityType);
            statement.executeUpdate(deleteSQL);

            PreparedStatement bulkInsert = null;
            sql = "insert into EntitySimilarity values(?,?,?,?,?);";

            try {

                connection.setAutoCommit(false);
                bulkInsert = connection.prepareStatement(sql);

                if (similarityType == SimilarityType.Jen_Sha_Div) {
                    for (String fromGrantId : similarityVectors.keySet()) {
                        boolean startCalc = false;

                        for (String toGrantId : similarityVectors.keySet()) {
                            if (!fromGrantId.equals(toGrantId) && !startCalc) {
                                continue;
                            } else {
                                startCalc = true;
                                similarity = Maths.jensenShannonDivergence(similarityVectors.get(fromGrantId), similarityVectors.get(toGrantId)); // the function returns distance not similarity
                                if (similarity > similarityThreshold && !fromGrantId.equals(toGrantId)) {

                                    bulkInsert.setInt(1, entityType);
                                    bulkInsert.setString(2, fromGrantId);
                                    bulkInsert.setString(3, toGrantId);
                                    bulkInsert.setDouble(4, (double) Math.round(similarity * 1000) / 1000);
                                    bulkInsert.setString(5, experimentId);
                                    bulkInsert.executeUpdate();
                                }
                            }
                        }
                    }
                } else if (similarityType == SimilarityType.cos) {
                    for (String fromGrantId : labelVectors.keySet()) {
                        boolean startCalc = false;

                        for (String toGrantId : labelVectors.keySet()) {
                            if (!fromGrantId.equals(toGrantId) && !startCalc) {
                                continue;
                            } else {
                                startCalc = true;
                                similarity = 1 - Math.abs(cosineSimilarity.distance(labelVectors.get(fromGrantId), labelVectors.get(toGrantId))); // the function returns distance not similarity
                                if (similarity > similarityThreshold && !fromGrantId.equals(toGrantId)) {
                                    bulkInsert.setInt(1, entityType);
                                    bulkInsert.setString(2, fromGrantId);
                                    bulkInsert.setString(3, toGrantId);
                                    bulkInsert.setDouble(4, (double) Math.round(similarity * 1000) / 1000);
                                    bulkInsert.setString(5, experimentId);
                                    bulkInsert.executeUpdate();
                                }
                            }
                        }
                    }
                }
                connection.commit();

            } catch (SQLException e) {

                if (connection != null) {
                    try {
                        logger.error("Transaction is being rolled back");
                        connection.rollback();
                    } catch (SQLException excep) {
                        logger.error("Error in insert grantSimilarity");
                    }
                }
            } finally {

                if (bulkInsert != null) {
                    bulkInsert.close();
                }
                connection.setAutoCommit(true);
            }

        } catch (SQLException e) {
            // if the error message is "out of memory", 
            // it probably means no database file is found
            logger.error(e.getMessage());
        } finally {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (SQLException e) {
                // connection close failed.
                logger.error(e);
            }
        }

        logger.info("similarities calculation finished");
    }

    public InstanceList[] GenerateAlphabets(String SQLConnection, ExperimentType experimentType, String dictDir, byte numModalities,
            int pruneCnt, int pruneLblCnt, double pruneMaxPerc, double pruneMinPerc, int numChars, Net2BoWType PPRenabled, boolean ignoreText) {

        //String txtAlphabetFile = dictDir + File.separator + "dict[0].txt";
        // Begin by importing documents from text to feature sequences
        ArrayList<Pipe> pipeListText = new ArrayList<Pipe>();

        // Pipes: lowercase, tokenize, remove stopwords, map to features
        pipeListText.add(new Input2CharSequence()); //homer
        pipeListText.add(new CharSequenceLowercase());

        SimpleTokenizer tokenizer = new SimpleTokenizer(new File("stoplists/en.txt"));
        pipeListText.add(tokenizer);

        Alphabet alphabet = new Alphabet();
        pipeListText.add(new StringList2FeatureSequence(alphabet));
        pipeListText.add(new FeatureSequenceRemovePlural(alphabet));

        ArrayList<ArrayList<Instance>> instanceBuffer = new ArrayList<ArrayList<Instance>>(numModalities);
        InstanceList[] instances = new InstanceList[numModalities];

        if (!ignoreText) {
            instances[0] = new InstanceList(new SerialPipes(pipeListText));
        }
        // Other Modalities
        for (byte m = ignoreText ? (byte) 0 : (byte) 1; m < numModalities; m++) {
            Alphabet alphabetM = new Alphabet();
            ArrayList<Pipe> pipeListCSV = new ArrayList<Pipe>();
            pipeListCSV.add(new CSV2FeatureSequence(alphabetM, ","));
//            if (experimentType == ExperimentType.ACM || experimentType == ExperimentType.DBLP || experimentType == ExperimentType.DBLP_ACM) {
//                pipeListCSV.add(new CSV2FeatureSequence(alphabetM, ","));
//            } else {
//                pipeListCSV.add(new CSV2FeatureSequence(alphabetM, ";"));
//            }
            instances[m] = new InstanceList(new SerialPipes(pipeListCSV));
        }

        //createCitationGraphFile("C:\\projects\\Datasets\\DBLPManage\\acm_output_NET.csv", "jdbc:sqlite:C:/projects/Datasets/DBLPManage/acm_output.db");
        for (byte m = 0; m < numModalities; m++) {
            instanceBuffer.add(new ArrayList<Instance>());

        }

        Connection connection = null;
        try {

            connection = DriverManager.getConnection(SQLConnection);
            connection.setAutoCommit(false);

            String sql = "";
            String txtsql = "select pubId, text, fulltext from pubviewtxt";

            if (experimentType == ExperimentType.ACM) {

                if (PPRenabled == Net2BoWType.PPR) {
                    sql = " select  pubId,  authors, citations, categories, period, keywords, venue, DBPediaResources from pubview  ";
                } else if (PPRenabled == Net2BoWType.OneWay) {

                    sql = " select  pubId, authors, citations, categories, keywords, venue, DBPediaResources from pubviewoneway ";
                } else if (PPRenabled == Net2BoWType.TwoWay) {
                    sql = " select  pubId, authors, citations, categories, keywords, venue, DBPediaResources from pubviewtwoway ";

                }

            } else if (experimentType == ExperimentType.OpenAIRE) {
                sql = " select   pubId,  citations,  keywords from pubviewsideinfo";

            }

            logger.info(" Getting text from the database");
            // get txt data 
            Statement txtstatement = connection.createStatement();
            txtstatement.setFetchSize(10000);
            txtstatement.setQueryTimeout(800);  // set timeout to 30 sec.
            ResultSet rstxt = txtstatement.executeQuery(txtsql);

            while (rstxt.next()) {

                String txt = "";
                switch (experimentType) {

                    case ACM:
                    case OpenAIRE:
                        txt = rstxt.getString("text");
                        instanceBuffer.get(0).add(new Instance(txt.substring(0, Math.min(txt.length() - 1, numChars)), null, rstxt.getString("pubId"), "text"));

                        break;

                    default:
                }
            }

            if (numModalities > 1) {
                logger.info(" Getting side info from the database");
                Statement statement = connection.createStatement();
                statement.setFetchSize(10000);
                statement.setQueryTimeout(800);  // set timeout to 30 sec.
                ResultSet rs = statement.executeQuery(sql);

                while (rs.next()) {
                    // read the result set

                    switch (experimentType) {

                        case ACM:
//                        instanceBuffer.get(0).add(new Instance(rs.getString("Text"), null, rs.getString("pubId"), "text"));
                            //String txt = rs.getString("text");
                            //instanceBuffer.get(0).add(new Instance(txt.substring(0, Math.min(txt.length() - 1, numChars)), null, rs.getString("pubId"), "text"));

                            if (numModalities > 1) {
                                String tmpJournalStr = rs.getString("Keywords");//.replace("\t", ",");
                                if (tmpJournalStr != null && !tmpJournalStr.equals("")) {
                                    instanceBuffer.get(1).add(new Instance(tmpJournalStr.replace('-', ' ').toLowerCase(), null, rs.getString("pubId"), "Keywords"));
                                }
                            }

                            if (numModalities > 2) {
                                String tmpStr = rs.getString("Categories");//.replace("\t", ",");
                                if (tmpStr != null && !tmpStr.equals("")) {

                                    instanceBuffer.get(2).add(new Instance(tmpStr, null, rs.getString("pubId"), "category"));
                                }
                            }

                            if (numModalities > 3) {
                                String tmpAuthorsStr = rs.getString("Venue");//.replace("\t", ",");
                                if (tmpAuthorsStr != null && !tmpAuthorsStr.equals("")) {

                                    instanceBuffer.get(3).add(new Instance(tmpAuthorsStr, null, rs.getString("pubId"), "Venue"));
                                }
                            }

                            if (numModalities > 4) {
                                String tmpStr = rs.getString("Citations");//.replace("\t", ",");
                                String citationStr = "";
                                if (tmpStr != null && !tmpStr.equals("")) {
                                    String[] citations = tmpStr.trim().split(",");
                                    for (int j = 0; j < citations.length; j++) {
                                        String[] pairs = citations[j].trim().split(":");
                                        if (pairs.length == 2) {
                                            for (int i = 0; i < Integer.parseInt(pairs[1]); i++) {
                                                citationStr += pairs[0] + ",";
                                            }
                                        } else {
                                            citationStr += citations[j] + ",";

                                        }
                                    }
                                    citationStr = citationStr.substring(0, citationStr.length() - 1);
                                    instanceBuffer.get(4).add(new Instance(citationStr, null, rs.getString("pubId"), "citation"));
                                }
                            }

//DBPediaResources
                            if (numModalities > 5) {
                                String tmpStr = rs.getString("DBPediaResources");//.replace("\t", ",");
                                String DBPediaResourceStr = "";
                                if (tmpStr != null && !tmpStr.equals("")) {
                                    String[] DBPediaResources = tmpStr.trim().split(",");
                                    for (int j = 0; j < DBPediaResources.length; j++) {
                                        String[] pairs = DBPediaResources[j].trim().split(";");
                                        if (pairs.length == 2) {
                                            for (int i = 0; i < Integer.parseInt(pairs[1]); i++) {
                                                DBPediaResourceStr += pairs[0] + ",";
                                            }
                                        } else {
                                            DBPediaResourceStr += DBPediaResources[j] + ",";

                                        }
                                    }
                                    DBPediaResourceStr = DBPediaResourceStr.substring(0, DBPediaResourceStr.length() - 1);
                                    instanceBuffer.get(5).add(new Instance(DBPediaResourceStr, null, rs.getString("pubId"), "DBPediaResource"));
                                }
                            }

                            if (numModalities > 6) {
                                String tmpAuthorsStr = rs.getString("Authors");//.replace("\t", ",");
                                if (tmpAuthorsStr != null && !tmpAuthorsStr.equals("")) {

                                    instanceBuffer.get(6).add(new Instance(tmpAuthorsStr, null, rs.getString("pubId"), "author"));
                                }
                            }

                            if (numModalities > 7) {
                                String tmpPeriod = rs.getString("Period");//.replace("\t", ",");
                                if (tmpPeriod != null && !tmpPeriod.equals("")) {

                                    instanceBuffer.get(7).add(new Instance(tmpPeriod, null, rs.getString("pubId"), "period"));
                                }
                            }

                            break;
                        case OpenAIRE:

                            if (numModalities > 1) {
                                String tmpJournalStr = rs.getString("Keywords");//.replace("\t", ",");
                                if (tmpJournalStr != null && !tmpJournalStr.equals("")) {
                                    instanceBuffer.get(1).add(new Instance(tmpJournalStr.replace('-', ' ').toLowerCase(), null, rs.getString("pubId"), "Keywords"));
                                }
                            }

                            if (numModalities > 2) {
                                String tmpStr = rs.getString("Citations");//.replace("\t", ",");
                                String citationStr = "";
                                if (tmpStr != null && !tmpStr.equals("")) {
                                    String[] citations = tmpStr.trim().split(",");
                                    for (int j = 0; j < citations.length; j++) {
                                        String[] pairs = citations[j].trim().split(":");
                                        if (pairs.length == 2) {
                                            for (int i = 0; i < Integer.parseInt(pairs[1]); i++) {
                                                citationStr += pairs[0] + ",";
                                            }
                                        } else {
                                            citationStr += citations[j] + ",";

                                        }
                                    }
                                    citationStr = citationStr.substring(0, citationStr.length() - 1);
                                    instanceBuffer.get(2).add(new Instance(citationStr, null, rs.getString("pubId"), "citation"));
                                }
                            }

                            break;

                        default:
                    }

                }
            }

            if (numModalities > 3 && experimentType == ExperimentType.OpenAIRE) {
                logger.info(" Getting DBpedia annotations from the database");
                // get txt data 
                Statement dbPediastatement = connection.createStatement();
                dbPediastatement.setFetchSize(10000);
                dbPediastatement.setQueryTimeout(800);  // set timeout to 30 sec.
                ResultSet rs = txtstatement.executeQuery("select   pubId,  DBPediaResources from pubviewdbpedia");

                while (rs.next()) {
                    String tmpStr = rs.getString("DBPediaResources");//.replace("\t", ",");
                    String DBPediaResourceStr = "";
                    if (tmpStr != null && !tmpStr.equals("")) {
                        String[] DBPediaResources = tmpStr.trim().split(",");
                        for (int j = 0; j < DBPediaResources.length; j++) {
                            String[] pairs = DBPediaResources[j].trim().split(";");
                            if (pairs.length == 2) {
                                for (int i = 0; i < Integer.parseInt(pairs[1]); i++) {
                                    DBPediaResourceStr += pairs[0] + ",";
                                }
                            } else {
                                DBPediaResourceStr += DBPediaResources[j] + ",";

                            }
                        }
                        DBPediaResourceStr = DBPediaResourceStr.substring(0, DBPediaResourceStr.length() - 1);
                        instanceBuffer.get(3).add(new Instance(DBPediaResourceStr, null, rs.getString("pubId"), "DBPediaResource"));
                    }
                }
            }

            if (numModalities > 4 && experimentType == ExperimentType.OpenAIRE) {
                logger.info(" Getting funding info from the database");
                // get txt data 
                Statement dbfundingstatement = connection.createStatement();
                dbfundingstatement.setFetchSize(10000);
                dbfundingstatement.setQueryTimeout(800);  // set timeout to 30 sec.
                ResultSet rs = txtstatement.executeQuery("select   pubId,  fundings from pubviewfunding");

                while (rs.next()) {

                    String tmpFundingStr = rs.getString("Fundings");//.replace("\t", ",");
                    if (tmpFundingStr != null && !tmpFundingStr.equals("")) {

                        instanceBuffer.get(4).add(new Instance(tmpFundingStr, null, rs.getString("pubId"), "Funding"));
                    }

                }
            }

        } catch (SQLException e) {
            // if the error message is "out of memory", 
            // it probably means no database file is found
            logger.error(e.getMessage());

        } finally {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (SQLException e) {
                // connection close failed.
                logger.error(e.getMessage());

            }
        }

        logger.info("Read " + instanceBuffer.get(0).size() + " instances modality: " + instanceBuffer.get(0).get(0).getSource().toString());

        if (!ignoreText) {
            try {
                GenerateStoplist(tokenizer, instanceBuffer.get(0), pruneCnt, pruneMaxPerc, pruneMinPerc, false);
                instances[0].addThruPipe(instanceBuffer.get(0).iterator());
                //Alphabet tmpAlp = instances[0].getDataAlphabet();
                //ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(new File(txtAlphabetFile)));
                //oos.writeObject(tmpAlp);
                //oos.close();
            } catch (IOException e) {
                logger.error("Problem adding text: "
                        + e);

            }
        }

        for (byte m = ignoreText ? (byte) 0 : (byte) 1; m < numModalities; m++) {
            logger.info("Read " + instanceBuffer.get(m).size() + " instances modality: " + (instanceBuffer.get(m).size() > 0 ? instanceBuffer.get(m).get(0).getSource().toString() : m));
            //instances[m] = new InstanceList(new SerialPipes(pipeListCSV));
            instances[m].addThruPipe(instanceBuffer.get(m).iterator());
        }

        logger.info(" instances added through pipe");

        // pruning for all other modalities no text
        for (byte m = ignoreText ? (byte) 0 : (byte) 1; m < numModalities; m++) {
            if ((m == 0 && pruneCnt > 0) || (m > 0 && pruneLblCnt > 0)) {

                // Check which type of data element the instances contain
                Instance firstInstance = instances[m].get(0);
                if (firstInstance.getData() instanceof FeatureSequence) {
                    // Version for feature sequences

                    Alphabet oldAlphabet = instances[m].getDataAlphabet();
                    Alphabet newAlphabet = new Alphabet();

                    // It's necessary to create a new instance list in
                    //  order to make sure that the data alphabet is correct.
                    Noop newPipe = new Noop(newAlphabet, instances[m].getTargetAlphabet());
                    InstanceList newInstanceList = new InstanceList(newPipe);

                    // Iterate over the instances in the old list, adding
                    //  up occurrences of features.
                    int numFeatures = oldAlphabet.size();
                    double[] counts = new double[numFeatures];
                    for (int ii = 0; ii < instances[m].size(); ii++) {
                        Instance instance = instances[m].get(ii);
                        FeatureSequence fs = (FeatureSequence) instance.getData();

                        fs.addFeatureWeightsTo(counts);
                    }

                    Instance instance;

                    // Next, iterate over the same list again, adding 
                    //  each instance to the new list after pruning.
                    while (instances[m].size() > 0) {
                        instance = instances[m].get(0);
                        FeatureSequence fs = (FeatureSequence) instance.getData();

                        fs.prune(counts, newAlphabet, ((m == 4 && experimentType == ExperimentType.ACM && PPRenabled == Net2BoWType.PPR) || (m == 2 && experimentType == ExperimentType.HEALTHTender)) ? pruneLblCnt * 3 : pruneLblCnt);

                        newInstanceList.add(newPipe.instanceFrom(new Instance(fs, instance.getTarget(),
                                instance.getName(),
                                instance.getSource())));

                        instances[m].remove(0);
                    }

//                logger.info("features: " + oldAlphabet.size()
                    //                       + " -> " + newAlphabet.size());
                    // Make the new list the official list.
                    instances[m] = newInstanceList;
                    // Alphabet tmp = newInstanceList.getDataAlphabet();
//                    String modAlphabetFile = dictDir + File.separator + "dict[" + m + "].txt";
//                    try {
//                        ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(new File(modAlphabetFile)));
//                        oos.writeObject(tmp);
//                        oos.close();
//                    } catch (IOException e) {
//                        logger.error("Problem serializing modality " + m + " alphabet to file "
//                                + txtAlphabetFile + ": " + e);
//                    }

                } else {
                    throw new UnsupportedOperationException("Pruning features from "
                            + firstInstance.getClass().getName()
                            + " is not currently supported");
                }

            }
        }
        return instances;

    }

    public void createRefACMTables(String SQLLitedb) {
        //String SQLConnectionString = "jdbc:sqlite:C:/projects/OpenAIRE/fundedarxiv.db";

        Connection connection = null;
        try {

            connection = DriverManager.getConnection(SQLLitedb);

            Statement statement = connection.createStatement();
            statement.executeUpdate("create table if not exists Author (AuthorId nvarchar(50), FirstName nvarchar(50), LastName nvarchar(50), MiddleName nvarchar(10), Affilication TEXT) ");
            String deleteSQL = String.format("Delete from Author ");
            statement.executeUpdate(deleteSQL);

            statement.executeUpdate("create table if not exists Citation (CitationId nvarchar(50), Reference TEXT) ");
            deleteSQL = String.format("Delete from Citation ");
            statement.executeUpdate(deleteSQL);

            statement.executeUpdate("create table if not exists Category (Category TEXT) ");
            deleteSQL = String.format("Delete from Category ");
            statement.executeUpdate(deleteSQL);

            statement = connection.createStatement();
            statement.executeUpdate("create table if not exists PubAuthor (PubId nvarchar(50), AuthorId nvarchar(50)) ");
            deleteSQL = String.format("Delete from PubAuthor ");
            statement.executeUpdate(deleteSQL);

            statement.executeUpdate("create table if not exists PubCitation (PubId nvarchar(50), CitationId nvarchar(50)) ");
            deleteSQL = String.format("Delete from PubCitation ");
            statement.executeUpdate(deleteSQL);

            statement.executeUpdate("create table if not exists PubCategory (PubId nvarchar(50), Category TEXT) ");
            deleteSQL = String.format("Delete from PubCategory ");
            statement.executeUpdate(deleteSQL);

            PreparedStatement authorBulkInsert = null;
            PreparedStatement citationBulkInsert = null;
            PreparedStatement categoryBulkInsert = null;

            PreparedStatement pubAuthorBulkInsert = null;
            PreparedStatement pubCitationBulkInsert = null;
            PreparedStatement pubCategoryBulkInsert = null;

            String authorInsertsql = "insert into Author values(?,?,?,?,?);";
            String citationInsertsql = "insert into Citation values(?,?);";
            String categoryInsertsql = "insert into Category values(?);";

            String pubAuthorInsertsql = "insert into pubAuthor values(?,?);";
            String pubCitationInsertsql = "insert into pubCitation values(?,?);";
            String pubCategoryInsertsql = "insert into pubCategory values(?,?);";

            TObjectIntHashMap<String> authorsLst = new TObjectIntHashMap<String>();
            TObjectIntHashMap<String> citationsLst = new TObjectIntHashMap<String>();
            TObjectIntHashMap<String> categorysLst = new TObjectIntHashMap<String>();

            try {

                connection.setAutoCommit(false);
                authorBulkInsert = connection.prepareStatement(authorInsertsql);
                citationBulkInsert = connection.prepareStatement(citationInsertsql);
                categoryBulkInsert = connection.prepareStatement(categoryInsertsql);

                pubAuthorBulkInsert = connection.prepareStatement(pubAuthorInsertsql);
                pubCitationBulkInsert = connection.prepareStatement(pubCitationInsertsql);
                pubCategoryBulkInsert = connection.prepareStatement(pubCategoryInsertsql);

                String sql = "	Select articleid,authors_id,authors_firstname,authors_lastname,authors_middlename,authors_affiliation,authors_role, \n"
                        + "			ref_objid,reftext,primarycategory,othercategory \n"
                        + "			 from ACMData1 \n";
                // + "			  LIMIT 10";

                statement.setQueryTimeout(30);  // set timeout to 30 sec.

                ResultSet rs = statement.executeQuery(sql);

                while (rs.next()) {
                    // read the result set
                    String Id = rs.getString("articleid");

                    String authorIdsStr = rs.getString("authors_id");
                    String[] authorIds = authorIdsStr.split("\t");

                    String authors_firstnamesStr = rs.getString("authors_firstname");
                    String[] authors_firstnames = authors_firstnamesStr.split("\t");

                    String authors_lastnamesStr = rs.getString("authors_lastname");
                    String[] authors_lastnames = authors_lastnamesStr.split("\t");

                    String authors_middlenamesStr = rs.getString("authors_middlename");
                    String[] authors_middlenames = authors_middlenamesStr.split("\t");

                    String authors_affiliationsStr = rs.getString("authors_affiliation");
                    String[] authors_affiliations = authors_affiliationsStr.split("\t");

                    for (int i = 0; i < authorIds.length - 1; i++) {
                        String authorId = authorIds[i];
                        if (!authorsLst.containsKey(authorId)) {
                            authorsLst.put(authorId, 1);
                            String lstName = authors_lastnames.length - 1 > i ? authors_lastnames[i] : "";
                            String fstName = authors_firstnames.length - 1 > i ? authors_firstnames[i] : "";
                            String mName = authors_middlenames.length - 1 > i ? authors_middlenames[i] : "";
                            String affiliation = authors_affiliations.length - 1 > i ? authors_affiliations[i] : "";

                            authorBulkInsert.setString(1, authorId);
                            authorBulkInsert.setString(2, lstName);
                            authorBulkInsert.setString(3, fstName);
                            authorBulkInsert.setString(4, mName);
                            authorBulkInsert.setString(5, affiliation);
                            authorBulkInsert.executeUpdate();
                        }
                        pubAuthorBulkInsert.setString(1, Id);
                        pubAuthorBulkInsert.setString(2, authorId);
                        pubAuthorBulkInsert.executeUpdate();

                    }

                    String citationIdsStr = rs.getString("ref_objid");
                    String[] citationIds = citationIdsStr.split("\t");

                    String citationsStr = rs.getString("reftext");
                    String[] citations = citationsStr.split("\t");

                    for (int i = 0; i < citationIds.length - 1; i++) {
                        String citationId = citationIds[i];
                        if (!citationsLst.containsKey(citationId)) {
                            citationsLst.put(citationId, 1);
                            String ref = citations.length - 1 > i ? citations[i] : "";

                            citationBulkInsert.setString(1, citationId);
                            citationBulkInsert.setString(2, ref);
                            citationBulkInsert.executeUpdate();
                        }
                        pubCitationBulkInsert.setString(1, Id);
                        pubCitationBulkInsert.setString(2, citationId);
                        pubCitationBulkInsert.executeUpdate();
                    }

                    String prCategoriesStr = rs.getString("primarycategory");
                    String[] prCategories = prCategoriesStr.split("\t");

                    String categoriesStr = rs.getString("othercategory");
                    String[] categories = categoriesStr.split("\t");

                    for (int i = 0; i < prCategories.length - 1; i++) {
                        String category = prCategories[i];
                        if (!categorysLst.containsKey(category)) {
                            categorysLst.put(category, 1);
                            categoryBulkInsert.setString(1, category);
                            categoryBulkInsert.executeUpdate();
                        }
                        pubCategoryBulkInsert.setString(1, Id);
                        pubCategoryBulkInsert.setString(2, category);
                        pubCategoryBulkInsert.executeUpdate();
                    }

                    for (int i = 0; i < categories.length - 1; i++) {
                        String category = categories[i];
                        if (!categorysLst.containsKey(category)) {
                            categorysLst.put(category, 1);
                            categoryBulkInsert.setString(1, category);
                            categoryBulkInsert.executeUpdate();
                        }

                        pubCategoryBulkInsert.setString(1, Id);
                        pubCategoryBulkInsert.setString(2, category);
                        pubCategoryBulkInsert.executeUpdate();
                    }

                }

                connection.commit();

            } catch (SQLException e) {

                logger.error("Error in creatingrefACMTables: " + e.getMessage());
                if (connection != null) {
                    try {
                        logger.error("Transaction is being rolled back");

                        connection.rollback();
                    } catch (SQLException excep) {
                        logger.error("Error in ACMReferences extraction" + excep);
                    }
                }
            } finally {

                if (authorBulkInsert != null) {
                    authorBulkInsert.close();
                }
                if (categoryBulkInsert != null) {
                    categoryBulkInsert.close();
                }
                if (citationBulkInsert != null) {
                    citationBulkInsert.close();
                }
                connection.setAutoCommit(true);
            }

        } catch (SQLException e) {
            // if the error message is "out of memory", 
            // it probably means no database file is found
            logger.error(e.getMessage());
        } catch (Exception e) {
            logger.error("File input error: " + e);
        } finally {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (SQLException e) {
                // connection close failed.
                logger.error(e);
            }
        }

    }

    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        //Class.forName("org.sqlite.JDBC");
        PTMFlow trainer = new PTMFlow();

    }
}
