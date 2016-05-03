/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package retriever;

import indexer.TrecDocIndexer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;
import trec.TRECQuery;
import wvec.DocVec;
import wvec.WordVec;
import wvec.WordVecs;

/**
 * Use the vector based representation of documents and queries to
 * compute similarity values.
 * Reranks the list of top docs by adding the contributions from the
 * vector based similarities. Currently, we do a linear combination after
 * similarity score normalization (of the text based scores)
 * 
 * @author Debasis
 */
public class DocVecSimilarity {
    TopDocs topDocs;
    TRECQuery query;
    int numDocs;
    IndexReader reader;
    DocVec queryVec;
    Properties prop;
    HashMap<String, SetSimilarityMeasure> simMeasures;    
    VecSimRetriever retriver;
    
    boolean compressedIndex, vocabCluster;
    float textSimWt;
    
    public DocVecSimilarity(VecSimRetriever retriever, TopDocs topDocs, TRECQuery query, float textSimWt) throws Exception {
        this.retriver = retriever;
        this.reader = retriever.reader;
        this.prop = retriever.prop;
        this.topDocs = topDocs;
        this.query = query;
        numDocs = topDocs.scoreDocs.length;
        
        // The vector representation for the query
        queryVec = new DocVec(query.getLuceneQueryObj());
        
        this.textSimWt = textSimWt;
        
        simMeasures = new HashMap<>();
        simMeasures.put("single-link", new SingleLinkageSim());
        simMeasures.put("complete-link", new CompleteLinkageSim());
        simMeasures.put("centroid-link", new CentroidLinkageSim(this.prop, this.reader));
        simMeasures.put("grpavg-link", new GroupAvgSim());
        simMeasures.put("haussdorf", new HausdorffSim());
        
        compressedIndex = Boolean.parseBoolean(prop.getProperty("index.compressed", "true"));
        this.vocabCluster = retriever.numVocabClusters > 0;
    }

    HashMap<String, WordVec> loadWordClusterInfo(int docId) throws Exception {
        String termText;
        BytesRef term;
        Terms tfvector;
        TermsEnum termsEnum;
        HashMap<Integer, List<WordVec>> clusterMap = new HashMap<>();
        
        tfvector = retriver.reader.getTermVector(docId, TrecDocIndexer.FIELD_ANALYZED_CONTENT);
        
        // Construct the normalized tf vector
        termsEnum = tfvector.iterator(null); // access the terms for this field
        
        //System.out.println("Getting cluster ids for document " + docId);
    	while ((term = termsEnum.next()) != null) { // explore the terms for this field
            termText = term.utf8ToString();
            int clusterId = WordVecs.getClusterId(termText);
            if (clusterId < 0)
                continue;
            
            // Get the term and its cluster id.. Store in a hashmap for
            // computing group-wise centroids
            WordVec wv = WordVecs.getVecCached(termText);
            List<WordVec> veclist = clusterMap.get(clusterId);
            if (veclist == null) {
                veclist = new ArrayList<>();
                clusterMap.put(clusterId, veclist);
            }
            veclist.add(wv);
        }
        //System.out.println("Got cluster ids for doc " + docId);

        // Return a list of centroids computed by grouping together the cluster ids
        HashMap<String, WordVec> centroids = new HashMap<>();
        
        //System.out.println("#clusters in doc " + docId + ": " + clusterMap.size());
        for (Map.Entry<Integer, List<WordVec>> e : clusterMap.entrySet()) {
            List<WordVec> veclist = e.getValue();
            WordVec centroid = WordVecs.getCentroid(veclist);
            centroids.put("Cluster: " + e.getKey(), centroid);
        }
        
        return centroids;
    }
    
    ScoreDoc[] computeSims() throws Exception {
        
        SetSimilarityMeasure simMeasure = simMeasures.get(prop.getProperty("sim_measure", "single-link"));
        float simValue;
        ScoreDoc[] scoreDocs = new ScoreDoc[numDocs];
        
        int i = 0;
        DocVec docvec;
        for (ScoreDoc sd : topDocs.scoreDocs) {
            Document doc = reader.document(sd.doc);
            
            // Two control flows depending on the type of the word clusters
            // we are interested in... i.e. global or per document...
            // the former is stored in an auxiliary index, latter stored
            // as auxiliary field of the retrievable document index...
            if (vocabCluster) {
                HashMap<String, WordVec> wvmap = loadWordClusterInfo(sd.doc);
                docvec = new DocVec(wvmap);
            }
            else {
                docvec = new DocVec(doc, compressedIndex);
            }
            
            //simValue = textSimWt*sd.score + (1-textSimWt)*simMeasure.computeSim(queryVec, docvec);
            simValue = simMeasure.computeSim(queryVec, docvec);
            scoreDocs[i++] = new ScoreDoc(sd.doc, simValue);
        }
        
        return scoreDocs;
    }       
}
