package ai.vespa.cloud.docsearch;

import ai.vespa.hosted.cd.Endpoint;
import ai.vespa.hosted.cd.SystemTest;
import ai.vespa.hosted.cd.TestRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SystemTest
public class VespaDocSystemTest {

    Endpoint testEndpoint = TestRuntime.get().deploymentToTest().endpoint("default");
    ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testFeedSearchDelete() throws Exception {
        feedTestDocs("/test-documents.json");

        HashSet<String> ids = getTestDocIDs();
        ids.forEach(id -> {System.out.println(id);});
        assertEquals(5, ids.size(), "test-documents.json has 5 documents");

        String allQuery = "select * from sources * where sddocname contains \"doc\";";
        String result = search(allQuery, "5s"); // use high timeout for first query
        assertEquals(5, getNumSearchResults(result));

        String someQuery = "select * from sources * where content contains \"access\";";
        result = search(someQuery, "500ms");
        Set<String> expectedResults = Set.of(
                "id:open:doc::open/documentation/access-logging.html",
                "id:open:doc::open/documentation/content/api-state-rest-api.html",
                "id:open:doc::open/documentation/operations/admin-procedures.html");
        assertEquals(expectedResults.size(), getNumSearchResults(result));
        Iterator<JsonNode> results = mapper.readTree(result).get("root").get("children").iterator();
        while (results.hasNext()) {
            assertTrue(expectedResults.contains(results.next().get("id").asText()));
        }

        removeTestDocs(ids);
        ids = getTestDocIDs();
        assertEquals(0, ids.size(), "visit all docs and remove");
    }

    @Test
    public void testUpdate() throws Exception {
        updateTestDocs("/test-documents-updates.json");

        HashSet<String> ids = getTestDocIDs();
        ids.forEach(id -> {System.out.println(id);});
        assertEquals(5, ids.size(), "test-documents-updates.json has 5 documents");

        removeTestDocs(ids);
        ids = getTestDocIDs();
        assertEquals(0, ids.size(), "visit all docs and remove then");
    }

    @Test
    public void testInlinks() throws Exception {
        feedTestDocs("/test-documents.json");
        HashSet<String> ids = getTestDocIDs();
        assertEquals(5, ids.size());

        verifyInlinks();

        removeTestDocs(ids);

        updateTestDocs("/test-documents-updates.json");
        ids = getTestDocIDs();
        assertEquals(5, ids.size());

        verifyInlinks();
    }

    private void verifyInlinks() throws IOException {
        int numLinks = 0;
        String docWithOneInLink = getTestDoc("id:open:doc::open/documentation/access-logging.html");
        JsonNode inlinks = mapper.readTree(docWithOneInLink).get("fields").get("inlinks");
        assertNotNull(inlinks, "Assert there is an inlink field in the document");
        Iterator<JsonNode> inlinksIter = inlinks.iterator();
        while (inlinksIter.hasNext()) {
            inlinksIter.next();
            numLinks++;
        }
        assertEquals(1, numLinks);

        numLinks = 0;
        String docWithTwoInLinks = getTestDoc("id:open:doc::open/documentation/operations/admin-procedures.html");
        inlinks = mapper.readTree(docWithTwoInLinks).get("fields").get("inlinks");
        assertNotNull(inlinks, "Assert there is an inlink field in the document");
        inlinksIter = inlinks.iterator();
        while (inlinksIter.hasNext()) {
            inlinksIter.next();
            numLinks++;
        }
        assertEquals(2, numLinks);
    }

    private void removeTestDocs(HashSet<String> ids) {
        System.out.println("** Start removing documents");
        ids.forEach(id -> {removeTestDoc(id);});
    }

    public void feedTestDocs(String testDocs) throws IOException {
        JsonNode docs = mapper.readTree(VespaDocSystemTest.class.getResourceAsStream(testDocs));
        Iterator<JsonNode> nodes = docs.elements();
        System.out.println("** Start feeding test documents");
        int i=0;
        while (nodes.hasNext()) {
            HttpResponse<String> res = feedTestDoc(nodes.next());
            assert(200 == res.statusCode());
            i++;
        }
        System.out.println("** Did feed " + i + " documents");
    }

    public void updateTestDocs(String testDocs) throws IOException {
        JsonNode updates = mapper.readTree(VespaDocSystemTest.class.getResourceAsStream(testDocs));
        Iterator<JsonNode> nodes = updates.elements();
        System.out.println("** Start updating test documents");
        int i=0;
        while (nodes.hasNext()) {
            HttpResponse<String> res = updateTestDoc(nodes.next());
            assert(200 == res.statusCode());
            i++;
        }
        System.out.println("** Did update " + i + " documents");
    }

    private HashSet<String> getTestDocIDs() throws Exception {
        HashSet<String> ids = new HashSet<>();
        String continuation = "";
        System.out.println("** Start dumping document IDs");
        do {
            String visitResult = visitDocuments(continuation.isEmpty() ? "" : "?continuation=" + continuation);
            continuation = getContinuationElement(visitResult);
            Iterator<JsonNode> documents = mapper.readTree(visitResult).path("documents").iterator();
            while (documents.hasNext()) {
                ids.add(documents.next().path("id").asText());
            }
        } while (!continuation.isEmpty());
        return ids;
    }

    public String getContinuationElement(String visitResult) throws Exception {
        return mapper.readTree(visitResult).path("continuation").asText();
    }

    public HttpResponse<String> feedTestDoc(JsonNode doc) {
        return testEndpoint.send(testEndpoint
                .request("/document/v1/open/doc/docid/" + "open" + doc.get("fields").get("path").textValue())
                .POST(ofString(doc.toString())));
    }

    public HttpResponse<String> updateTestDoc(JsonNode update) {
        return testEndpoint.send(testEndpoint
                .request("/document/v1/open/doc/docid/" +
                                "open"+ update.get("fields").get("path").get("assign").textValue(),
                        Map.of("create", "true"))
                .PUT(ofString(update.toString())));
    }

    private void removeTestDoc(String id) {
        testEndpoint.send(testEndpoint
                .request("/document/v1/open/doc/docid/" + id.split(":")[4]) // id:open:doc::open/documentation/annotations.html
                .DELETE());
    }

    public String getTestDoc(String id) {
        HttpResponse<String> res = testEndpoint.send(testEndpoint
                .request("/document/v1/open/doc/docid/" + id.split(":")[4])
                .GET());
        return res.body();
    }

    public String search(String query, String timeout) {
        HttpResponse<String> res = testEndpoint.send(testEndpoint
                .request("/search/",
                        Map.of("yql", query,
                                "timeout", timeout)));
        return res.body();
    }

    public String visitDocuments(String continuation) {
        HttpResponse<String> res = testEndpoint.send(testEndpoint
                .request("/document/v1/open/doc/docid/" + continuation)
                .GET());
        return res.body();
    }

    public void prettyPrint(String json) throws Exception {
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(new ObjectMapper().readTree(json)));
    }

    public int getNumSearchResults(String searchResult) throws Exception {
        return mapper.readTree(searchResult).get("root").get("fields").get("totalCount").asInt();
    }
}
