package org.graylog2.indexer;

import com.beust.jcommander.internal.Maps;
import org.apache.commons.io.FileUtils;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.WriteConsistencyLevel;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.action.admin.indices.stats.IndexStats;
import org.elasticsearch.action.admin.indices.stats.IndicesStats;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsRequest;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.count.CountRequest;
import org.elasticsearch.action.deletebyquery.DeleteByQueryRequestBuilder;
import org.elasticsearch.action.deletebyquery.DeleteByQueryResponse;
import org.elasticsearch.action.index.IndexRequest.OpType;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.support.replication.ReplicationType;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.settings.loader.YamlSettingsLoader;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeBuilder;
import org.graylog2.Core;
import org.graylog2.activities.Activity;
import org.graylog2.plugin.logmessage.LogMessage;
import org.json.simple.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.index.query.QueryBuilders.rangeQuery;
import static org.elasticsearch.node.NodeBuilder.nodeBuilder;
import org.graylog2.plugin.indexer.MessageGateway;

// TODO this class blocks for most of its operations, but is called from the main thread for some of them
// TODO figure out how to gracefully deal with failure to connect (or losing connection) to the elastic search cluster!
public class EmbeddedElasticSearchClient {
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedElasticSearchClient.class);

    private Client client;
    private final MessageGateway messageGateway;
    public static final String TYPE = "message";

    // http://www.elasticsearch.org/guide/reference/index-modules/store.html
    public static final String STANDARD_RECENT_INDEX_STORE_TYPE = "niofs";
    public static final List<String> ALLOWED_RECENT_INDEX_STORE_TYPES = new ArrayList<String>() {{ 
        add("niofs");
        add("simplefs");
        add("mmapfs");
        add("memory");
    }};

    private Core server;

    public EmbeddedElasticSearchClient(Core graylogServer) {
        server = graylogServer;
        messageGateway = new MessageGatewayImpl(graylogServer);

        final NodeBuilder builder = nodeBuilder().client(true);
        String esSettings;
        Map<String, String> settings = null;
        try {
            esSettings = FileUtils.readFileToString(new File(graylogServer.getConfiguration().getElasticSearchConfigFile()));
            settings = new YamlSettingsLoader().load(esSettings);
        } catch (IOException e) {
            throw new RuntimeException("Cannot read elasticsearch configuration.", e);
        }
        builder.settings().put(settings);
        final Node node = builder.node();
        client = node.client();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                node.close();
            }
        });

    }
    
    public Client getClient() {
        return client;
    }

    public MessageGateway getMessageGateway() {
        return messageGateway;
    }
    
    public String allIndicesAlias() {
        return server.getConfiguration().getElasticSearchIndexPrefix() + "_*";
    }
    
    public String allIndicesExceptRecentIndexAlias() {
        // e.g. graylog2_*,-graylog2_recent
        return allIndicesAlias() + ",-" + server.getConfiguration().getRecentIndexName();
    }
    
    public long getTotalIndexSize() {
        return client.admin().indices().stats(
                new IndicesStatsRequest().indices(allIndicesAlias()))
                .actionGet()
                .total()
                .store()
                .getSize()
                .getMb();
    }
    
    public String nodeIdToName(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) {
            return null;
        }
        
        try {
            NodesInfoResponse r = client.admin().cluster().nodesInfo(new NodesInfoRequest(nodeId).all()).actionGet();
            return r.getNodesMap().get(nodeId).getNode().getName();
        } catch (Exception e) {
            LOG.error("Could not read name of ES node.", e);
            return "UNKNOWN";
        }
        
    }
    
    public String nodeIdToHostName(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) {
            return null;
        }
        
        try {
            NodesInfoResponse r = client.admin().cluster().nodesInfo(new NodesInfoRequest(nodeId).all()).actionGet();
            return r.getNodesMap().get(nodeId).getHostname();
        } catch (Exception e) {
            LOG.error("Could not read name of ES node.", e);
            return "UNKNOWN";
        }
        
    }
    
    public int getNumberOfNodesInCluster() {
        return client.admin().cluster().nodesInfo(new NodesInfoRequest().all()).actionGet().nodes().length;
    }
    
    public long getTotalNumberOfMessagesInIndices() {
        return client.count(new CountRequest(allIndicesAlias())).actionGet().count();
    }
    
    public Map<String, IndexStats> getIndices() {
        ActionFuture<IndicesStats> isr = client.admin().indices().stats(new IndicesStatsRequest().all());
        
        return isr.actionGet().indices();
    }

    public IndexStats getRecentIndex() {
        return getIndices().get(server.getConfiguration().getRecentIndexName());
    }
    
    public ImmutableMap<String, IndexMetaData> getIndicesMetadata() {
        return client.admin().cluster().state(new ClusterStateRequest().filteredIndices(server.getConfiguration().getRecentIndexName())).actionGet().getState().getMetaData().indices();
    }
    
    public String getRecentIndexStorageType() {
        return getIndicesMetadata().get(server.getConfiguration().getRecentIndexName()).getSettings().get("index.store.type");
    }
    
    public boolean indexExists(String index) {
        ActionFuture<IndicesExistsResponse> existsFuture = client.admin().indices().exists(new IndicesExistsRequest(index));
        return existsFuture.actionGet().exists();
    }

    public boolean createIndex(String indexName) {
        Map<String, Integer> settings = Maps.newHashMap();
        settings.put("number_of_shards", server.getConfiguration().getElasticSearchShards());
        settings.put("number_of_replicas", server.getConfiguration().getElasticSearchReplicas());

        CreateIndexRequest cir = new CreateIndexRequest(indexName);
        cir.settings(settings);
        
        final ActionFuture<CreateIndexResponse> createFuture = client.admin().indices().create(cir);
        final boolean acknowledged = createFuture.actionGet().acknowledged();
        if (!acknowledged) {
            return false;
        }
        final PutMappingRequest mappingRequest = Mapping.getPutMappingRequest(client, indexName, server.getConfiguration().getElasticSearchAnalyzer());
        final boolean mappingCreated = client.admin().indices().putMapping(mappingRequest).actionGet().acknowledged();
        return acknowledged && mappingCreated;
    }
    
    public boolean createRecentIndex() {
        Map<String, Object> settings = Maps.newHashMap();
        settings.put("store.type", server.getConfiguration().getRecentIndexStoreType());
        settings.put("number_of_shards", server.getConfiguration().getElasticSearchShards());
        settings.put("number_of_replicas", server.getConfiguration().getElasticSearchReplicas());
        
        CreateIndexRequestBuilder crb = new CreateIndexRequestBuilder(client.admin().indices());
        crb.setIndex(server.getConfiguration().getRecentIndexName());
        crb.setSettings(settings);
        
        final ActionFuture<CreateIndexResponse> createFuture = client.admin().indices().create(crb.request());
        final boolean acknowledged = createFuture.actionGet().acknowledged();
        if (!acknowledged) {
            return false;
        }
        final PutMappingRequest mappingRequest = Mapping.getPutMappingRequest(client, server.getConfiguration().getRecentIndexName(), server.getConfiguration().getElasticSearchAnalyzer());
        final boolean mappingCreated = client.admin().indices().putMapping(mappingRequest).actionGet().acknowledged();
        return acknowledged && mappingCreated;
    }
    
    public boolean cycleAlias(String aliasName, String targetIndex) {
        return client.admin().indices().prepareAliases()
                .addAlias(targetIndex, aliasName)
                .execute().actionGet().acknowledged();
    }
    
    public boolean cycleAlias(String aliasName, String targetIndex, String oldIndex) {
        return client.admin().indices().prepareAliases()
                .removeAlias(oldIndex, aliasName)
                .addAlias(targetIndex, aliasName)
                .execute().actionGet().acknowledged();
    }
    
    public long numberOfMessages(String indexName) throws IndexNotFoundException {
        Map<String, IndexStats> indices = getIndices();
        IndexStats index = indices.get(indexName);
        
        if (index == null) {
            throw new IndexNotFoundException();
        }
        
        return index.getPrimaries().docs().count();
    }

    public boolean bulkIndex(final List<LogMessage> messages) {
        if (messages.isEmpty()) {
            return true;
        }

        final BulkRequestBuilder mainIndex = client.prepareBulk();
        final BulkRequestBuilder recentIndex = client.prepareBulk();
        for (LogMessage msg : messages) {
            String source = JSONValue.toJSONString(msg.toElasticSearchObject());

            // we manually set the document ID to the same value to be able to match up documents later.
            mainIndex.add(buildIndexRequest(Deflector.DEFLECTOR_NAME, source, msg.getId(), 0)); // Main index.
            recentIndex.add(buildIndexRequest(server.getConfiguration().getRecentIndexName(), source, msg.getId(), server.getConfiguration().getRecentIndexTtlMinutes())); // Recent index.
        }

        mainIndex.setConsistencyLevel(WriteConsistencyLevel.ONE);
        recentIndex.setConsistencyLevel(WriteConsistencyLevel.ONE);
        
        mainIndex.setReplicationType(ReplicationType.ASYNC);
        recentIndex.setReplicationType(ReplicationType.ASYNC);
        
        final ActionFuture<BulkResponse> mainBulkFuture = client.bulk(mainIndex.request());
        final ActionFuture<BulkResponse> recentBulkFuture = client.bulk(recentIndex.request());
        
        final BulkResponse mainResponse = mainBulkFuture.actionGet();
        final BulkResponse recentResponse = recentBulkFuture.actionGet();
        
        LOG.debug("Deflector index: Bulk indexed {} messages, took {} ms, failures: {}",
                new Object[] { mainResponse.items().length, mainResponse.getTookInMillis(), mainResponse.hasFailures() });
        
        LOG.debug("Recent index: Bulk indexed {} messages, took {} ms, failures: {}",
                new Object[] { recentResponse.items().length, recentResponse.getTookInMillis(), recentResponse.hasFailures() });
        
        return !mainResponse.hasFailures() && !recentResponse.hasFailures();
    }

    public void deleteMessagesByTimeRange(int to) {
        DeleteByQueryRequestBuilder b = client.prepareDeleteByQuery();
        final QueryBuilder qb = rangeQuery("created_at").from(0).to(to);
        
        b.setTypes(new String[] {TYPE});
        b.setIndices(server.getDeflector().getAllDeflectorIndexNames());
        b.setQuery(qb);
        
        ActionFuture<DeleteByQueryResponse> future = client.deleteByQuery(b.request());
        future.actionGet();
    }
    
    public void deleteIndex(String indexName) {
        client.admin().indices().delete(new DeleteIndexRequest(indexName)).actionGet();
    }
    
    public void runIndexRetention() throws NoTargetIndexException {
        Map<String, IndexStats> indices = server.getDeflector().getAllDeflectorIndices();
        int indexCount = indices.size();
        int maxIndices = server.getConfiguration().getMaxNumberOfIndices();
        
        // Do we have more indices than the configured maximum?
        if (indexCount <= maxIndices) {
            LOG.debug("Number of indices ({}) lower than limit ({}). Not performing any retention actions.",
                    indexCount, maxIndices);
            return;
        }
        
        // We have more indices than the configured maximum! Remove as many as needed.
        int remove = indexCount-maxIndices;
        String msg = "Number of indices (" + indexCount + ") higher than limit (" + maxIndices + "). Deleting " + remove + " indices.";
        LOG.info(msg);
        server.getActivityWriter().write(new Activity(msg, EmbeddedElasticSearchClient.class));
        
        for (String indexName : IndexHelper.getOldestIndices(indices.keySet(), remove)) {
            // Never delete the current deflector target.
            if (server.getDeflector().getCurrentTargetName().equals(indexName)) {
                LOG.info("Not deleting current deflector target <{}>.", indexName);
                continue;
            }
            
            msg = "Retention cleaning: Deleting index <" + indexName + ">";
            LOG.info(msg);
            server.getActivityWriter().write(new Activity(msg, EmbeddedElasticSearchClient.class));
            
            // Sorry if this should ever go mad. Delete the index!
            deleteIndex(indexName);
            server.getMongoBridge().removeIndexDateRange(indexName);
        }
    }
    
    private IndexRequestBuilder buildIndexRequest(String index, String source, String id, int ttlMinutes) {
        final IndexRequestBuilder b = new IndexRequestBuilder(client);
        
        /*
         * ID is set manually to allow inserting message into recent and total index
         * with same ID. (Required for linking in frontend)
         */
        b.setId(id);
        b.setSource(source);
        b.setIndex(index);
        b.setContentType(XContentType.JSON);
        b.setOpType(OpType.INDEX);
        b.setType(TYPE);
        b.setConsistencyLevel(WriteConsistencyLevel.ONE);

        // Set a TTL?
        if (ttlMinutes > 0) {
            b.setTTL(ttlMinutes*60*1000); // TTL is specified in milliseconds.
        }
        
        return b;
    }

}
