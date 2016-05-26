/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package indexer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;
import org.apache.xalan.xsltc.compiler.util.Type;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import wvec.DocVec;
import wvec.WordVecs;

/**
 *
 * @author Debasis
 */

public class TrecDocIndexer {
    Properties prop;
    File indexDir;
    IndexWriter writer;
    Analyzer analyzer;
    List<String> stopwords;
    int numClusters;
    boolean useVocabClustering;
    
    static final public String FIELD_ID = "id";
    static final public String FIELD_ANALYZED_CONTENT = "words";  // Standard analyzer w/o stopwords.
    // Each document is represented by a set of cluster centre points
    static final public String FIELD_WORDVEC_CLUSTER_CENTRES = "vecs";  

    protected List<String> buildStopwordList(String stopwordFileName) {
        List<String> stopwords = new ArrayList<>();
        String stopFile = prop.getProperty(stopwordFileName);        
        String line;

        try (FileReader fr = new FileReader(stopFile);
            BufferedReader br = new BufferedReader(fr)) {
            while ( (line = br.readLine()) != null ) {
                stopwords.add(line.trim());
            }
            br.close();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
        return stopwords;
    }

    Analyzer constructAnalyzer() {
        Analyzer eanalyzer = new EnglishAnalyzer(
            Version.LUCENE_4_10_0,
            StopFilter.makeStopSet(
                Version.LUCENE_4_10_0, buildStopwordList("stopfile"))); // default analyzer
        HashMap<String, Analyzer> aMap = new HashMap<>(2);
        aMap.put(FIELD_ANALYZED_CONTENT, eanalyzer);
        aMap.put(FIELD_WORDVEC_CLUSTER_CENTRES, new StandardAnalyzer());
        
        PerFieldAnalyzerWrapper pfw = new PerFieldAnalyzerWrapper(eanalyzer, aMap);
        return pfw;        
    }
    
    public TrecDocIndexer(String propFile) throws Exception {
        prop = new Properties();
        prop.load(new FileReader(propFile));                
        analyzer = constructAnalyzer();            
        String indexPath = prop.getProperty("index");        
        indexDir = new File(indexPath);
        numClusters = Integer.parseInt(prop.getProperty("wvecs.numclusters", "5"));
        useVocabClustering = Boolean.parseBoolean(prop.getProperty("index.clustervocab", "true"));
    }
    
    public Analyzer getAnalyzer() { return analyzer; }
    
    public Properties getProperties() { return prop; }
    
    void processAll() throws Exception {
        System.out.println("Indexing TREC collection...");
        
        IndexWriterConfig iwcfg = new IndexWriterConfig(Version.LUCENE_4_9, analyzer);
        iwcfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        writer = new IndexWriter(FSDirectory.open(indexDir), iwcfg);
        
        indexAll();
        
        writer.close();
    }
    
    public File getIndexDir() { return indexDir; }
        
    void indexAll() throws Exception {
        if (writer == null) {
            System.err.println("Skipping indexing... Index already exists at " + indexDir.getName() + "!!");
            return;
        }
        
        File topDir = new File(prop.getProperty("coll"));
        indexDirectory(topDir);
    }

    private void indexDirectory(File dir) throws Exception {
        File[] files = dir.listFiles();
        for (int i=0; i < files.length; i++) {
            File f = files[i];
            if (f.isDirectory()) {
                System.out.println("Indexing directory " + f.getName());
                indexDirectory(f);  // recurse
            }
            else
                indexFile(f);
        }
    }
    
    String getDocVec(String id, String text) throws Exception {
        DocVec dvec = new DocVec(id, text);
        return dvec.getClusterVecs(numClusters);
    }
    
    Document constructDoc(String id, String content) throws Exception {
        Document doc = new Document();
        doc.add(new Field(FIELD_ID, id, Field.Store.YES, Field.Index.NOT_ANALYZED));

        if (!useVocabClustering) {
            StringBuffer tokenizedContentBuff = new StringBuffer();
            TokenStream stream = analyzer.tokenStream(FIELD_ANALYZED_CONTENT, new StringReader(content));
            CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);
            stream.reset();

            while (stream.incrementToken()) {
                String term = termAtt.toString();
                tokenizedContentBuff.append(term).append(" ");
            }

            stream.end();
            stream.close();

            // Form the document vector object
            String docVec = getDocVec(id, tokenizedContentBuff.toString());
            BytesRef docVecBytes = CompressionUtils.compress(docVec);
            
            // For the 1st pass, use a standard analyzer to write out
            // the words (also store the term vector)
            doc.add(new Field(FIELD_ANALYZED_CONTENT, content,
                    Field.Store.NO, Field.Index.ANALYZED, Field.TermVector.YES));
            doc.add(new StoredField(FIELD_WORDVEC_CLUSTER_CENTRES, docVecBytes));
        }
        else {
            // dont add any additional cluster specific fields
            doc.add(new Field(FIELD_ANALYZED_CONTENT, content,
                    Field.Store.NO, Field.Index.ANALYZED, Field.TermVector.YES));
        }
        
        return doc;
    }

    void indexFile(File file) throws Exception {
        FileReader fr = new FileReader(file);
        BufferedReader br = new BufferedReader(fr);
        String line;
        Document doc;

        System.out.println("Indexing file: " + file.getName());
        
        StringBuffer txtbuff = new StringBuffer();
        while ((line = br.readLine()) != null)
            txtbuff.append(line).append("\n");
        String content = txtbuff.toString();

        org.jsoup.nodes.Document jdoc = Jsoup.parse(content);
        Elements docElts = jdoc.select("DOC");

        for (Element docElt : docElts) {
            Element docIdElt = docElt.select("DOCNO").first();
            doc = constructDoc(docIdElt.text(), docElt.text());
            writer.addDocument(doc);
        }
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            args = new String[1];
            System.out.println("Usage: java TrecDocIndexer <prop-file>");
            args[0] = "init.properties";
        }

        try {
            WordVecs.init(args[0]);
            TrecDocIndexer indexer = new TrecDocIndexer(args[0]);
            indexer.processAll();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }    
}
