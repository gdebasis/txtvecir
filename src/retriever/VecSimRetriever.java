/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package retriever;

import evaluator.Evaluator;
import indexer.TrecDocIndexer;
import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.similarities.DefaultSimilarity;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.store.FSDirectory;
import trec.TRECQuery;
import trec.TRECQueryParser;
import wvec.WordVecs;

/**
 *
 * @author Debasis
 */

class ScoreDocComparator implements Comparator<ScoreDoc> {

    @Override
    public int compare(ScoreDoc thisSd, ScoreDoc thatSd) {
        return -1*Float.compare(thisSd.score, thatSd.score);  // descending
    }
}

public class VecSimRetriever {
    TrecDocIndexer indexer;
    IndexReader reader;
    IndexReader clusterInfoReader;
    IndexSearcher searcher;
    IndexSearcher clusterIdSearcher;
    int numWanted;
    Properties prop;
    String runName;
    boolean useVecSim;
    float textSimWt;
    String resultsFile;
    int numVocabClusters;
    
    public VecSimRetriever(String propFile) throws Exception {
        this.indexer = new TrecDocIndexer(propFile);
        this.prop = indexer.getProperties();
        
        File indexDir = indexer.getIndexDir();
        System.out.println("Running queries against index: " + indexDir.getPath());
        reader = DirectoryReader.open(FSDirectory.open(indexDir));
                
        numVocabClusters = Integer.parseInt(prop.getProperty("retrieve.vocabcluster.numclusters", "0"));
        if (numVocabClusters > 0) {
            String clusterInfoIndexPath = prop.getProperty("wvecs.clusterids.basedir") + "/" + numVocabClusters;
            clusterInfoReader = DirectoryReader.open(FSDirectory.open(new File(clusterInfoIndexPath)));
        }
        
        searcher = new IndexSearcher(reader);
        clusterIdSearcher = new IndexSearcher(clusterInfoReader);
        
        float lambda = 1.0f - Float.parseFloat(prop.getProperty("lm.lambda", "0.4"));
        searcher.setSimilarity(new LMJelinekMercerSimilarity(lambda));
        clusterIdSearcher.setSimilarity(new DefaultSimilarity());
        
        numWanted = Integer.parseInt(prop.getProperty("retrieve.num_wanted", "1000"));
        runName = prop.getProperty("retrieve.runname", "lm");        
        useVecSim = Boolean.parseBoolean(prop.getProperty("retrieve.vecsim", "true"));
        this.textSimWt = Float.parseFloat(prop.getProperty("simscore.textsim", "0.6"));        
    }
    
    public List<TRECQuery> constructQueries() throws Exception {        
        String queryFile = prop.getProperty("query.file");
        TRECQueryParser parser = new TRECQueryParser(queryFile, indexer.getAnalyzer());
        parser.parse();
        return parser.getQueries();
    }    

    ScoreDoc[] normalize(ScoreDoc[] sd, boolean sorted) {
        ScoreDoc[] normalizedScoreDocs = new ScoreDoc[sd.length];
        for (int i=0; i < sd.length; i++) {
            normalizedScoreDocs[i] = new ScoreDoc(sd[i].doc, sd[i].score);
        }
        
        float maxScore = 0;
        float sumScore = 0;
        
        for (int i=0; i < sd.length; i++) {
            if (sd[i].score > maxScore)
                maxScore = sd[i].score;
            sumScore += sd[i].score;
        }
        
        for (int i=0; i < sd.length; i++) {
            //normalizedScoreDocs[i].score = sd[i].score/maxScore;
            normalizedScoreDocs[i].score = sd[i].score/sumScore;
        }
        return normalizedScoreDocs;
    }
    
    // Try combining similarities in different ways.
    ScoreDoc[] combineSimilarities(ScoreDoc[] txtScores, ScoreDoc[] wvecScores) {
        // Normalize the scores
        ScoreDoc[] nTxtScores = normalize(txtScores, true);
        ScoreDoc[] nwvecScores = normalize(wvecScores, false);
        
        for (int i=0; i < txtScores.length; i++) {
            nTxtScores[i].score = this.textSimWt*nTxtScores[i].score +
                                (1-textSimWt)*nwvecScores[i].score;
        }
        
        /*
        for (int i=0; i < txtScores.length; i++) {
            txtScores[i].score = this.textSimWt*txtScores[i].score +
                                (1-textSimWt)*wvecScores[i].score;
        }
        */
        
        Arrays.sort(nTxtScores, new ScoreDocComparator());
        
        // Constitute the sublist
        int numWanted = Math.min(this.numWanted, nTxtScores.length);
        ScoreDoc[] topWanted = new ScoreDoc[numWanted];
        System.arraycopy(nTxtScores, 0, topWanted, 0, Math.min(numWanted, nTxtScores.length));
        
        System.out.println(topWanted[0].score + ", " + topWanted[numWanted-1].score);
        return topWanted;
    }
    
    public void retrieveAll() throws Exception {
        TopScoreDocCollector collector;
        TopDocs topDocs;
        resultsFile = prop.getProperty("res.file");        
        FileWriter fw = new FileWriter(resultsFile);
        
        List<TRECQuery> queries = constructQueries();
        
        for (TRECQuery query : queries) {

            // Print query
            System.out.println(query.getLuceneQueryObj());
            
            // Retrieve results (100 more than numwanted giving the other ones
            // a chance to be retrieved)
            collector = TopScoreDocCollector.create(useVecSim?numWanted+100:numWanted, true);
            searcher.search(query.getLuceneQueryObj(), collector);
            topDocs = collector.topDocs();
            
            TopDocs rerankedTopDocs;
            if (!useVecSim)
                rerankedTopDocs = topDocs;
            else {    
                // Compute doc-query vector based similarities
                DocVecSimilarity dvecSim = new DocVecSimilarity(this, topDocs, query, this.textSimWt);
                ScoreDoc[] wvecScoreDocs = dvecSim.computeSims();
                
                // Combine the similarity scores of the wvecs and the text
                ScoreDoc[] combinedScoreDocs = combineSimilarities(topDocs.scoreDocs, wvecScoreDocs);

                rerankedTopDocs = new TopDocs(
                        numWanted, combinedScoreDocs, combinedScoreDocs[0].score);
            }
            
            System.out.println("Retrieved results for query " + query.id);

            // Save results
            saveRetrievedTuples(fw, query, rerankedTopDocs);
        }
        
        fw.close();        
        reader.close();
        clusterInfoReader.close();
        
        if (Boolean.parseBoolean(prop.getProperty("eval"))) {
            evaluate();
        }
    }

    public void saveRetrievedTuples(FileWriter fw, TRECQuery query, TopDocs topDocs) throws Exception {
        StringBuffer buff = new StringBuffer();
        ScoreDoc[] hits = topDocs.scoreDocs;
        int len = Math.min(numWanted, hits.length);
        for (int i = 0; i < len; ++i) {
            int docId = hits[i].doc;
            Document d = searcher.doc(docId);
            buff.append(query.id.trim()).append("\tQ0\t").
                    append(d.get(TrecDocIndexer.FIELD_ID)).append("\t").
                    append((i+1)).append("\t").
                    append(hits[i].score).append("\t").
                    append(runName).append("\n");                
        }
        fw.write(buff.toString());        
    }
    
    public Properties getProperties() { return prop; }
    
    public void evaluate() throws Exception {
        Evaluator evaluator = new Evaluator(this.getProperties());
        evaluator.load();
        evaluator.fillRelInfo();
        System.out.println(evaluator.computeAll());        
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            args = new String[1];
            args[0] = "init.properties";
        }
        try {
            WordVecs.init(args[0]);
            VecSimRetriever searcher = new VecSimRetriever(args[0]);
            searcher.retrieveAll();            
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }    
}
