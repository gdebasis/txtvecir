/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package retriever;

import indexer.TrecDocIndexer;
import java.util.List;
import java.util.Properties;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import wvec.DocVec;
import wvec.WordVec;
import wvec.WordVecs;

/**
 *
 * @author Debasis
 */
interface SetSimilarityMeasure {
    public float computeSim(DocVec a, DocVec b) throws Exception;
}

class SingleLinkageSim implements SetSimilarityMeasure {

    @Override
    public float computeSim(DocVec a, DocVec b) throws Exception {
        List<WordVec> alist = a.getWordVecs();
        List<WordVec> blist = b.getWordVecs();

        float maxSim = 0, sim;
        for (WordVec avec : alist) {
            for (WordVec bvec : blist) {
                sim = WordVecs.getSim(avec, bvec);
                if (sim > maxSim)
                    maxSim = sim;
            }
        }
        return maxSim;
    }    
}

class CompleteLinkageSim implements SetSimilarityMeasure {

    @Override
    public float computeSim(DocVec a, DocVec b) throws Exception {
        List<WordVec> alist = a.getWordVecs();
        List<WordVec> blist = b.getWordVecs();

        float minSim = 1f, sim;
        for (WordVec avec : alist) {
            for (WordVec bvec : blist) {
                sim = WordVecs.getSim(avec, bvec);
                if (sim < minSim)
                    minSim = sim;
            }
        }
        return minSim;
    }    
}

// Works the best...
class CentroidLinkageSim implements SetSimilarityMeasure {

    Properties prop;
    boolean useIDFWeights;
    IndexReader reader;
    int numDocs;
    
    public CentroidLinkageSim(Properties prop, IndexReader reader) {
        this.prop = prop;
        this.reader = reader;
        useIDFWeights = Boolean.parseBoolean(prop.getProperty("centroidsim.weighted", "false"));
        numDocs = reader.numDocs();
    }
    
    @Override
    public float computeSim(DocVec a, DocVec b) throws Exception {
        List<WordVec> alist = a.getWordVecs();
        List<WordVec> blist = b.getWordVecs();

        float avgSim = 0;
        float thisSim;
        float normalizationFactor = 0;
        float wt = 1;
        
        for (WordVec avec : alist) {
            if (useIDFWeights) {
                float df = reader.docFreq(new Term(TrecDocIndexer.FIELD_ANALYZED_CONTENT, avec.getWord()));
                wt = (float)Math.log(numDocs/df);
            }
            
            for (WordVec bvec : blist) {
                thisSim = WordVecs.getSim(avec, bvec);
                avgSim += thisSim*wt;
                normalizationFactor = normalizationFactor + wt;
            }            
        }
        return avgSim/(float)(normalizationFactor);
    }    
}

class GroupAvgSim implements SetSimilarityMeasure {

    @Override
    public float computeSim(DocVec a, DocVec b) throws Exception {
        List<WordVec> alist = a.getWordVecs();
        List<WordVec> blist = b.getWordVecs();

        int alen = alist.size();
        int blen = blist.size();
        
        float avgSim = 0;
        
        // a vec list intrasim
        for (int i = 0; i < alen; i++) {            
            WordVec vec_i = alist.get(i);
            for (int j = i+1; j < alen; j++) {
                WordVec vec_j = alist.get(j);
                avgSim += WordVecs.getSim(vec_i, vec_j);
            }
        }
        
        // a vec list intrasim
        for (int i = 0; i < blen; i++) {            
            WordVec vec_i = blist.get(i);
            for (int j = i+1; j < blen; j++) {
                WordVec vec_j = blist.get(j);
                avgSim += WordVecs.getSim(vec_i, vec_j);
            }
        }
        
        for (WordVec avec : alist) {
            for (WordVec bvec : blist) {
                avgSim += WordVecs.getSim(avec, bvec);
            }
        }
        
        int totalNumComparisons = alen*(alen-1)/2 + blen*(blen-1)/2 + alen*blen;
        return avgSim/(float)(totalNumComparisons);
    }    
}

class HausdorffSim implements SetSimilarityMeasure {

    private float simAB(List<WordVec> alist, List<WordVec> blist) {
        float daB, dAB = 0;
        for (WordVec avec : alist) {
            daB = Float.MAX_VALUE;
            for (WordVec bvec : blist) {
                float thisSim = avec.cosineSim(bvec);
                if (thisSim < daB)
                    daB = thisSim;
            }
            if (daB > dAB)
                dAB = daB;
        }
        return dAB;
    }
    
    @Override
    public float computeSim(DocVec a, DocVec b) throws Exception {
        List<WordVec> alist = a.getWordVecs();
        List<WordVec> blist = b.getWordVecs();

        float dAB = simAB(alist, blist);
        float dBA = simAB(blist, alist);
        
        float hAB = Math.max(dAB, dBA);
        return (float)Math.exp(-hAB*hAB);
    }
    
}

