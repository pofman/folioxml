package folioxml.export.plugins;

import folioxml.config.*;
import folioxml.core.InvalidMarkupException;
import folioxml.core.Pair;
import folioxml.core.TokenUtils;
import folioxml.export.FileNode;
import folioxml.export.InfobaseSetPlugin;
import folioxml.export.LogStreamProvider;
import folioxml.lucene.InfobaseFieldOptsSet;
import folioxml.lucene.analysis.DynamicAnalyzer;
import folioxml.lucene.folioQueryParser.QueryParser;
import folioxml.slx.ISlxTokenReader;
import folioxml.slx.SlxRecord;
import folioxml.xml.Node;
import folioxml.xml.NodeFilter;
import folioxml.xml.NodeList;
import folioxml.xml.XmlRecord;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

//Fixes jump links and query links across infobase boundaries.
public class ResolveHyperlinks implements InfobaseSetPlugin {

    IndexSearcher searcher = null;

    InfobaseSet infobaseSet = null;

    Map<String, DynamicAnalyzer> analyzersPerInfobase = new HashMap<String, DynamicAnalyzer>();


    private DynamicAnalyzer loadAnalyzerFromLucene(InfobaseConfig ic) throws IOException, InvalidMarkupException {
        BooleanQuery.Builder q = new BooleanQuery.Builder();
		q.add(new TermQuery(new Term("infobase", ic.getId())), BooleanClause.Occur.SHOULD);
        q.add(new TermQuery(new Term("infobaseId", ic.getId())), BooleanClause.Occur.SHOULD);
        q.add(new TermQuery(new Term("level", "root")), BooleanClause.Occur.MUST);
        ScoreDoc[] hits = searcher.search(q.build(), 1).scoreDocs;
        if (hits.length > 0) {
            //info.workingQueryLinks++;
            String rootXml = searcher.doc(hits[0].doc).get("xml");
            XmlRecord root = new XmlRecord(rootXml);
            return new DynamicAnalyzer(new InfobaseFieldOptsSet(root));
        } else {
            //Infobase in set not indexed
            throw new IOException("Infobase " + ic.getId() + " is present in set, but root record is missing from lucene index.");
        }
    }

    private void loadAnalyzers() throws IOException, InvalidMarkupException {
        for (InfobaseConfig i : infobaseSet.getInfobases()) {
            analyzersPerInfobase.put(i.getId(), loadAnalyzerFromLucene(i));
        }
    }

    ExportLocations export;
    LogStreamProvider provider;
    Boolean resolve_jump_links;
    Boolean resolve_query_links;

    @Override
    public void beginInfobaseSet(InfobaseSet set, ExportLocations export, LogStreamProvider logs) throws IOException, InvalidMarkupException {
        infobaseSet = set;
        searcher = null;
        this.export = export;
        this.provider = logs;

        resolve_jump_links = set.getBool("resolve_jump_links");
        if (resolve_jump_links == null) resolve_jump_links = true;
        resolve_query_links = set.getBool("resolve_query_links");
        if (resolve_query_links == null) resolve_query_links = true;

		System.out.println("Resolve Jump Links: " + resolve_jump_links);
		System.out.println("Resolve Query Links: " + resolve_query_links);

        Path index = export.getLocalPath("lucene_index", AssetType.LuceneIndex, FolderCreation.None);

        if (java.nio.file.Files.isDirectory(index)) {
			System.out.println("Index directory file: " + index);
            searcher = new IndexSearcher(DirectoryReader.open(FSDirectory.open(index)));
            //Load and parse all infobase root nodes
            //query infoabse="x" && level="root", then load and parse slx to load the query resolver.
            loadAnalyzers();
        } else {
            System.err.println("Failed to locate lucene index; links will not be resolved");
        }
    }

    InfobaseConfig currentInfobase = null;

    @Override
    public void beginInfobase(InfobaseConfig infobase) throws IOException {
        currentInfobase = infobase;
    }

    @Override
    public ISlxTokenReader wrapSlxReader(ISlxTokenReader reader) {
        return reader;
    }

    @Override
    public void onSlxRecordParsed(SlxRecord clean_slx) throws InvalidMarkupException, IOException {

    }

    @Override
    public void onRecordTransformed(XmlRecord r, SlxRecord dirty_slx) throws InvalidMarkupException, IOException {


    }

    @Override
    public FileNode assignFileNode(XmlRecord xr, SlxRecord dirty_slx) throws InvalidMarkupException, IOException {
        return null;
    }

    @Override
    public void onRecordComplete(XmlRecord xr, FileNode file) throws InvalidMarkupException, IOException {
        if (searcher == null) return; //Do nothing if we can't access lucene.
        NodeList nodes = new NodeList(xr);


        if (resolve_jump_links) {
            //All jump destinations must be an anchor tag, or we can't link to them.
            for (Node n : nodes.search(new NodeFilter("bookmark")).list()) {
                n.set("id", hashDestination(currentInfobase, n.get("name")));
                n.setTagName("a");
            }
        }


        //Convert local and remote jump and query links


        NodeList queryLinks = nodes.search(new NodeFilter("link", "query", null));
        for (Node n : queryLinks.list()) {

            if (resolve_query_links) {
                Pair<String, String> result = TryGetResultUri(n.get("infobase"), TokenUtils.entityDecodeString(n.get("query")), file);
                n.set("resolved", result.getSecond());
                if (result.getFirst() != null) {
                    n.set("href", result.getFirst());
                    n.setTagName("a");
                } else {
                    provider.getNamedStream("broken_query_links").append("Broken query link").append(" in record ").append(n.rootNode().get("folioId")).append("\n").append(n.toXmlString((true))).append("\n");

                }
            } else {
                provider.getNamedStream("unresolved_query_links").append("Query link").append(" in record ").append(n.rootNode().get("folioId")).append("\n").append(n.toXmlString((true))).append("\n");
            }
        }


        //Convert  jump links
        NodeList jumpLinks = nodes.search(new NodeFilter("link", "jumpDestination", null));
        for (Node n : jumpLinks.list()) {
            if (resolve_jump_links) {
                Pair<String, String> result = TryGetDestinationUri(n.get("infobase"), n.get("jumpDestination"), file);
                n.set("resolved", result.getSecond());
                if (result.getFirst() != null) {
                    n.set("href", result.getFirst());
                    n.setTagName("a");
                } else {
                    provider.getNamedStream("broken_jump_links").append("Broken jump link").append(" in record ").append(n.rootNode().get("folioId")).append("\n").append(n.toXmlString((true))).append("\n");
                    n.pull();
                }
            } else {
                provider.getNamedStream("unresolved_jump_links").append("Jump link").append(" in record ").append(n.rootNode().get("folioId")).append("\n").append(n.toXmlString((true))).append("\n");
            }
        }
    }


    private String hashDestination(InfobaseConfig infobase, String name) {
        //Normalize destination infobase ID.
        String iid = infobase.getId();

        //Ids may not begin with a number, prefix
        return "d" + Integer.toHexString(iid.hashCode()) + "_" + Integer.toHexString(name.hashCode());
    }


    @Override
    public void endInfobase(InfobaseConfig infobase) throws IOException, InvalidMarkupException {

    }

    @Override
    public void endInfobaseSet(InfobaseSet set) throws IOException {
        if (searcher != null) searcher.getIndexReader().close();
    /*
        System.out.println("Invalid query links (for syntax reasons): " + queryInfo.invalidQueryLinks);
        System.out.println("No result query links: " + queryInfo.noresultQueryLinks);
        System.out.println("Working query links: " + queryInfo.workingQueryLinks);
        System.out.println("Cross-infobase query links: " + queryInfo.crossInfobaseQueries);
        */
    }

    public String GetUriFor(Document d, FileNode n, String overrideFragment) throws IOException {
        String relative_path = d.get("relative_path");
        String uri_fragment = d.get("uri_fragment");

        Path doc_base = export.getLocalPath(n.getRelativePath(), AssetType.Html, FolderCreation.None);

        return export.getUri(relative_path, AssetType.Html, doc_base) + ((overrideFragment == null) ? uri_fragment : overrideFragment);
    }


    public Pair<String, String> TryGetResultUri(String infobase, String query, FileNode fn) throws InvalidMarkupException, IOException {
        InfobaseConfig targetConfig = infobase == null ? currentInfobase : infobaseSet.byName(infobase);
        if (targetConfig == null) {
            return new Pair<String, String>(null, "destination infobase is external to configuration set");
        }
        Analyzer a = this.analyzersPerInfobase.get(targetConfig.getId());
        try {

            //Lookup analyzer based on infobase
            QueryParser qp = new QueryParser(a, InfobaseFieldOptsSet.getStaticDefaultField());
            Query q = qp.parse(query);
            if (q == null) {
                System.out.println("Failed to convert query: " + query);
                //info.invalidQueryLinks ++;
                return new Pair<String, String>(null, "failed to parse query");
            } else {
                String newQuery = q.toString();
                ScoreDoc[] hits = searcher.search(q, 1).scoreDocs;
                if (hits.length > 0) {
                    Document d = searcher.doc(hits[0].doc);
                    //info.workingQueryLinks++;
                    return new Pair<String, String>(GetUriFor(d, fn, null), "true");
                } else {

                    //info.noresultQueryLinks++;
                    System.out.println("No results for " + newQuery + "     (Previously " + query + ")");
                    return new Pair<String, String>(null, "no results for query " + newQuery);
                }
            }
        } catch (InvalidMarkupException ex) {
            System.out.println("Failed on: " + query);
            System.out.println(ex.getMessage());
            //info.invalidQueryLinks++;
            return new Pair<String, String>(null, "exception occurred: " + ex.toString());
        } catch (IOException e) {
            System.out.println("Failed on: " + query);
            // TODO Auto-generated catch block
            e.printStackTrace();
            // info.invalidQueryLinks++;
            return new Pair<String, String>(null, "exception occurred: " + e.toString());
        } /*catch (ParseException e) {
            System.out.println("Failed on: " + query);
            // TODO Auto-generated catch block
            e.printStackTrace();
            //info.invalidQueryLinks++;
            return new Pair<String, String>(null, "exception occurred: " + e.toString());
        }*/

    }

    public Pair<String, String> TryGetDestinationUri(String infobase, String jumpDestination, FileNode fn) throws IOException, InvalidMarkupException {
        InfobaseConfig targetConfig = infobase == null ? currentInfobase : infobaseSet.byName(infobase);
        if (targetConfig == null) {
            return new Pair<String, String>(null, "destination infobase is external to configuration set");
        }

        BooleanQuery.Builder qb = new BooleanQuery.Builder();
        qb.add(new TermQuery(new Term("infobase", targetConfig.getId())), BooleanClause.Occur.SHOULD);
		qb.add(new TermQuery(new Term("infobaseId", targetConfig.getId())), BooleanClause.Occur.SHOULD);
        qb.add(new TermQuery(new Term("destinations", jumpDestination)), BooleanClause.Occur.MUST);
        ScoreDoc[] hits = searcher.search(qb.build(), 1).scoreDocs;
        if (hits.length > 0) {
            String bookmarkHash = hashDestination(currentInfobase, jumpDestination);
            //info.workingQueryLinks++;
            String newUri = GetUriFor(searcher.doc(hits[0].doc), fn, "#" + bookmarkHash);
            //TODO: improve by modifying uri fragment to link directly to bookmark
            if (newUri == null) {
                //We aren't providing structure
                throw new InvalidMarkupException("Hyperlinks cannot be resolved unless ExportStructure (or another plugin that populates the uri attribute for records) is installed prior to indexing");
            }
            return new Pair<String, String>(newUri, "true");

        } else {
            //TODO; broken jump link!
            return new Pair<String, String>(null, "no corresponding jump destination found for infobase " + targetConfig.getId() + " and bookmark '" + jumpDestination + "'.");
        }
    }


}
