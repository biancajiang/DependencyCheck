/*
 * This file is part of dependency-check-core.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2012 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.data.lucene;

import java.io.IOException;
import java.util.HashMap;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.owasp.dependencycheck.BaseTest;

/**
 *
 * @author Jeremy Long
 */
public class FieldAnalyzerTest extends BaseTest {

    @Test
    public void testAnalyzers() throws Exception {

        Analyzer analyzer = new FieldAnalyzer(LuceneUtils.CURRENT_VERSION);
        Directory index = new RAMDirectory();

        String field1 = "product";
        String text1 = "springframework";

        String field2 = "vendor";
        String text2 = "springsource";

        try (IndexWriter w = createIndex(analyzer, index)) {
            addDoc(w, field1, text1, field2, text2);
            text1 = "x-stream";
            text2 = "xstream";
            addDoc(w, field1, text1, field2, text2);
        }

        //Analyzer searchingAnalyzer = new SearchFieldAnalyzer(LuceneUtils.CURRENT_VERSION);
        String querystr = "product:\"(Spring Framework Core)\" vendor:(SpringSource)";

        SearchFieldAnalyzer searchAnalyzerProduct = new SearchFieldAnalyzer(LuceneUtils.CURRENT_VERSION);
        SearchFieldAnalyzer searchAnalyzerVendor = new SearchFieldAnalyzer(LuceneUtils.CURRENT_VERSION);
        HashMap<String, Analyzer> map = new HashMap<>();
        map.put(field1, searchAnalyzerProduct);
        map.put(field2, searchAnalyzerVendor);
        PerFieldAnalyzerWrapper wrapper = new PerFieldAnalyzerWrapper(new StandardAnalyzer(LuceneUtils.CURRENT_VERSION), map);
        QueryParser parser = new QueryParser(LuceneUtils.CURRENT_VERSION, field1, wrapper);

        Query q = parser.parse(querystr);

        int hitsPerPage = 10;

        IndexReader reader = DirectoryReader.open(index);
        IndexSearcher searcher = new IndexSearcher(reader);
        TopScoreDocCollector collector = TopScoreDocCollector.create(hitsPerPage, true);
        searcher.search(q, collector);
        ScoreDoc[] hits = collector.topDocs().scoreDocs;

        assertEquals("Did not find 1 document?", 1, hits.length);
        assertEquals("springframework", searcher.doc(hits[0].doc).get(field1));
        assertEquals("springsource", searcher.doc(hits[0].doc).get(field2));

        searchAnalyzerProduct.clear(); //ensure we don't have anything left over from the previous search.
        searchAnalyzerVendor.clear();
        querystr = "product:(Apache Struts) vendor:(Apache)";
        Query q2 = parser.parse(querystr);
        assertFalse("second parsing contains previousWord from the TokenPairConcatenatingFilter", q2.toString().contains("core"));
        
        querystr = "product:(  x-stream^5 )  AND  vendor:(  thoughtworks.xstream )";
        Query q3 = parser.parse(querystr);
        collector = TopScoreDocCollector.create(hitsPerPage, true);
        searcher.search(q3, collector);
        hits = collector.topDocs().scoreDocs;
        assertEquals("x-stream", searcher.doc(hits[0].doc).get(field1));
        assertEquals("xstream", searcher.doc(hits[0].doc).get(field2));
    }

    private IndexWriter createIndex(Analyzer analyzer, Directory index) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(LuceneUtils.CURRENT_VERSION, analyzer);
        return new IndexWriter(index, config);
    }

    private static void addDoc(IndexWriter w, String field1, String text1, String field2, String text2) throws IOException {
        Document doc = new Document();
        doc.add(new TextField(field1, text1, Field.Store.YES));
        doc.add(new TextField(field2, text2, Field.Store.YES));
        w.addDocument(doc);
    }
}
