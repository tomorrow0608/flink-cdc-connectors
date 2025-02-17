/*
 * Copyright 2023 Ververica Inc.
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
 */

package com.ververica.cdc.connectors.mongodb.source;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.ververica.cdc.connectors.mongodb.source.config.MongoDBSourceConfig;
import com.ververica.cdc.connectors.mongodb.source.config.MongoDBSourceConfigFactory;
import com.ververica.cdc.connectors.mongodb.source.utils.MongoUtils;
import com.ververica.cdc.connectors.mongodb.utils.MongoDBTestUtils;
import org.bson.Document;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static com.ververica.cdc.connectors.mongodb.utils.MongoDBAssertUtils.assertEqualsInAnyOrder;
import static com.ververica.cdc.connectors.mongodb.utils.MongoDBContainer.FLINK_USER;
import static com.ververica.cdc.connectors.mongodb.utils.MongoDBContainer.FLINK_USER_PASSWORD;
import static com.ververica.cdc.connectors.mongodb.utils.MongoDBTestUtils.fetchRows;
import static com.ververica.cdc.connectors.mongodb.utils.MongoDBTestUtils.triggerFailover;
import static org.apache.flink.util.Preconditions.checkState;
import static org.junit.Assert.assertEquals;

/** Integration tests for MongoDB full document before change info. */
@RunWith(Parameterized.class)
public class MongoDBFullChangelogITCase extends MongoDBSourceTestBase {

    @Rule public final Timeout timeoutPerTest = Timeout.seconds(300);

    private final boolean parallelismSnapshot;

    public MongoDBFullChangelogITCase(boolean parallelismSnapshot) {
        this.parallelismSnapshot = parallelismSnapshot;
    }

    @Parameterized.Parameters(name = "parallelismSnapshot: {0}")
    public static Object[] parameters() {
        return new Object[][] {new Object[] {false}, new Object[] {true}};
    }

    @Test
    public void testGetMongoDBVersion() {
        MongoDBSourceConfig config =
                new MongoDBSourceConfigFactory()
                        .hosts(CONTAINER.getHostAndPort())
                        .splitSizeMB(1)
                        .pollAwaitTimeMillis(500)
                        .create(0);

        assertEquals(MongoUtils.getMongoVersion(config), "6.0.6");
    }

    @Test
    public void testReadSingleCollectionWithSingleParallelism() throws Exception {
        testMongoDBParallelSource(
                1,
                MongoDBTestUtils.FailoverType.NONE,
                MongoDBTestUtils.FailoverPhase.NEVER,
                new String[] {"customers"});
    }

    @Test
    public void testReadSingleCollectionWithMultipleParallelism() throws Exception {
        testMongoDBParallelSource(
                4,
                MongoDBTestUtils.FailoverType.NONE,
                MongoDBTestUtils.FailoverPhase.NEVER,
                new String[] {"customers"});
    }

    @Test
    public void testReadMultipleCollectionWithSingleParallelism() throws Exception {
        testMongoDBParallelSource(
                1,
                MongoDBTestUtils.FailoverType.NONE,
                MongoDBTestUtils.FailoverPhase.NEVER,
                new String[] {"customers", "customers_1"});
    }

    @Test
    public void testReadMultipleCollectionWithMultipleParallelism() throws Exception {
        testMongoDBParallelSource(
                4,
                MongoDBTestUtils.FailoverType.NONE,
                MongoDBTestUtils.FailoverPhase.NEVER,
                new String[] {"customers", "customers_1"});
    }

    // Failover tests
    @Test
    public void testTaskManagerFailoverInSnapshotPhase() throws Exception {
        if (!parallelismSnapshot) {
            return;
        }
        testMongoDBParallelSource(
                MongoDBTestUtils.FailoverType.TM,
                MongoDBTestUtils.FailoverPhase.SNAPSHOT,
                new String[] {"customers", "customers_1"});
    }

    @Test
    public void testTaskManagerFailoverInStreamPhase() throws Exception {
        if (!parallelismSnapshot) {
            return;
        }
        testMongoDBParallelSource(
                MongoDBTestUtils.FailoverType.TM,
                MongoDBTestUtils.FailoverPhase.STREAM,
                new String[] {"customers", "customers_1"});
    }

    @Test
    public void testJobManagerFailoverInSnapshotPhase() throws Exception {
        if (!parallelismSnapshot) {
            return;
        }
        testMongoDBParallelSource(
                MongoDBTestUtils.FailoverType.JM,
                MongoDBTestUtils.FailoverPhase.SNAPSHOT,
                new String[] {"customers", "customers_1"});
    }

    @Test
    public void testJobManagerFailoverInStreamPhase() throws Exception {
        if (!parallelismSnapshot) {
            return;
        }
        testMongoDBParallelSource(
                MongoDBTestUtils.FailoverType.JM,
                MongoDBTestUtils.FailoverPhase.STREAM,
                new String[] {"customers", "customers_1"});
    }

    @Test
    public void testTaskManagerFailoverSingleParallelism() throws Exception {
        if (!parallelismSnapshot) {
            return;
        }
        testMongoDBParallelSource(
                1,
                MongoDBTestUtils.FailoverType.TM,
                MongoDBTestUtils.FailoverPhase.SNAPSHOT,
                new String[] {"customers"});
    }

    @Test
    public void testJobManagerFailoverSingleParallelism() throws Exception {
        if (!parallelismSnapshot) {
            return;
        }
        testMongoDBParallelSource(
                1,
                MongoDBTestUtils.FailoverType.JM,
                MongoDBTestUtils.FailoverPhase.SNAPSHOT,
                new String[] {"customers"});
    }

    private void testMongoDBParallelSource(
            MongoDBTestUtils.FailoverType failoverType,
            MongoDBTestUtils.FailoverPhase failoverPhase,
            String[] captureCustomerCollections)
            throws Exception {
        testMongoDBParallelSource(
                DEFAULT_PARALLELISM, failoverType, failoverPhase, captureCustomerCollections);
    }

    private void testMongoDBParallelSource(
            int parallelism,
            MongoDBTestUtils.FailoverType failoverType,
            MongoDBTestUtils.FailoverPhase failoverPhase,
            String[] captureCustomerCollections)
            throws Exception {

        String customerDatabase =
                "customer_" + Integer.toUnsignedString(new Random().nextInt(), 36);

        // A - enable system-level fulldoc pre & post image feature
        CONTAINER.executeCommand(
                "use admin; db.runCommand({ setClusterParameter: { changeStreamOptions: { preAndPostImages: { expireAfterSeconds: 'off' } } } })");

        // B - enable collection-level fulldoc pre & post image for change capture collection
        for (String collectionName : captureCustomerCollections) {
            CONTAINER.executeCommandInDatabase(
                    String.format(
                            "db.createCollection('%s'); db.runCommand({ collMod: '%s', changeStreamPreAndPostImages: { enabled: true } })",
                            collectionName, collectionName),
                    customerDatabase);
        }

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        StreamTableEnvironment tEnv = StreamTableEnvironment.create(env);

        env.setParallelism(parallelism);
        env.enableCheckpointing(200L);
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(1, 0));

        String sourceDDL =
                String.format(
                        "CREATE TABLE customers ("
                                + " _id STRING NOT NULL,"
                                + " cid BIGINT NOT NULL,"
                                + " name STRING,"
                                + " address STRING,"
                                + " phone_number STRING,"
                                + " primary key (_id) not enforced"
                                + ") WITH ("
                                + " 'connector' = 'mongodb-cdc',"
                                + " 'scan.incremental.snapshot.enabled' = '%s',"
                                + " 'hosts' = '%s',"
                                + " 'username' = '%s',"
                                + " 'password' = '%s',"
                                + " 'database' = '%s',"
                                + " 'collection' = '%s',"
                                + " 'heartbeat.interval.ms' = '500',"
                                + " 'scan.full-changelog' = 'true'"
                                + ")",
                        parallelismSnapshot ? "true" : "false",
                        CONTAINER.getHostAndPort(),
                        FLINK_USER,
                        FLINK_USER_PASSWORD,
                        customerDatabase,
                        getCollectionNameRegex(customerDatabase, captureCustomerCollections));

        CONTAINER.executeCommandFileInDatabase("customer", customerDatabase);

        // first step: check the snapshot data
        String[] snapshotForSingleTable =
                new String[] {
                    "+I[101, user_1, Shanghai, 123567891234]",
                    "+I[102, user_2, Shanghai, 123567891234]",
                    "+I[103, user_3, Shanghai, 123567891234]",
                    "+I[109, user_4, Shanghai, 123567891234]",
                    "+I[110, user_5, Shanghai, 123567891234]",
                    "+I[111, user_6, Shanghai, 123567891234]",
                    "+I[118, user_7, Shanghai, 123567891234]",
                    "+I[121, user_8, Shanghai, 123567891234]",
                    "+I[123, user_9, Shanghai, 123567891234]",
                    "+I[1009, user_10, Shanghai, 123567891234]",
                    "+I[1010, user_11, Shanghai, 123567891234]",
                    "+I[1011, user_12, Shanghai, 123567891234]",
                    "+I[1012, user_13, Shanghai, 123567891234]",
                    "+I[1013, user_14, Shanghai, 123567891234]",
                    "+I[1014, user_15, Shanghai, 123567891234]",
                    "+I[1015, user_16, Shanghai, 123567891234]",
                    "+I[1016, user_17, Shanghai, 123567891234]",
                    "+I[1017, user_18, Shanghai, 123567891234]",
                    "+I[1018, user_19, Shanghai, 123567891234]",
                    "+I[1019, user_20, Shanghai, 123567891234]",
                    "+I[2000, user_21, Shanghai, 123567891234]"
                };
        tEnv.executeSql(sourceDDL);
        TableResult tableResult =
                tEnv.executeSql("select cid, name, address, phone_number from customers");
        CloseableIterator<Row> iterator = tableResult.collect();
        JobID jobId = tableResult.getJobClient().get().getJobID();
        List<String> expectedSnapshotData = new ArrayList<>();
        for (int i = 0; i < captureCustomerCollections.length; i++) {
            expectedSnapshotData.addAll(Arrays.asList(snapshotForSingleTable));
        }

        // trigger failover after some snapshot splits read finished
        if (failoverPhase == MongoDBTestUtils.FailoverPhase.SNAPSHOT && iterator.hasNext()) {
            triggerFailover(
                    failoverType, jobId, miniClusterResource.getMiniCluster(), () -> sleepMs(100));
        }

        assertEqualsInAnyOrder(
                expectedSnapshotData, fetchRows(iterator, expectedSnapshotData.size()));

        // second step: check the change stream data
        for (String collectionName : captureCustomerCollections) {
            makeFirstPartChangeStreamEvents(
                    mongodbClient.getDatabase(customerDatabase), collectionName);
        }
        if (failoverPhase == MongoDBTestUtils.FailoverPhase.STREAM) {
            triggerFailover(
                    failoverType, jobId, miniClusterResource.getMiniCluster(), () -> sleepMs(200));
        }
        for (String collectionName : captureCustomerCollections) {
            makeSecondPartChangeStreamEvents(
                    mongodbClient.getDatabase(customerDatabase), collectionName);
        }

        String[] changeEventsForSingleTable =
                new String[] {
                    "-U[101, user_1, Shanghai, 123567891234]",
                    "+U[101, user_1, Hangzhou, 123567891234]",
                    "-D[102, user_2, Shanghai, 123567891234]",
                    "+I[102, user_2, Shanghai, 123567891234]",
                    "-U[103, user_3, Shanghai, 123567891234]",
                    "+U[103, user_3, Hangzhou, 123567891234]",
                    "-U[1010, user_11, Shanghai, 123567891234]",
                    "+U[1010, user_11, Hangzhou, 123567891234]",
                    "+I[2001, user_22, Shanghai, 123567891234]",
                    "+I[2002, user_23, Shanghai, 123567891234]",
                    "+I[2003, user_24, Shanghai, 123567891234]"
                };
        List<String> expectedChangeStreamData = new ArrayList<>();
        for (int i = 0; i < captureCustomerCollections.length; i++) {
            expectedChangeStreamData.addAll(Arrays.asList(changeEventsForSingleTable));
        }
        List<String> actualChangeStreamData = fetchRows(iterator, expectedChangeStreamData.size());
        assertEqualsInAnyOrder(expectedChangeStreamData, actualChangeStreamData);
        tableResult.getJobClient().get().cancel().get();
    }

    private String getCollectionNameRegex(String database, String[] captureCustomerCollections) {
        checkState(captureCustomerCollections.length > 0);
        if (captureCustomerCollections.length == 1) {
            return captureCustomerCollections[0];
        } else {
            // pattern that matches multiple collections
            return Arrays.stream(captureCustomerCollections)
                    .map(coll -> "^(" + database + "." + coll + ")$")
                    .collect(Collectors.joining("|"));
        }
    }

    private void sleepMs(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }

    private void makeFirstPartChangeStreamEvents(MongoDatabase mongoDatabase, String collection) {
        MongoCollection<Document> mongoCollection = mongoDatabase.getCollection(collection);
        mongoCollection.updateOne(Filters.eq("cid", 101L), Updates.set("address", "Hangzhou"));
        mongoCollection.deleteOne(Filters.eq("cid", 102L));
        mongoCollection.insertOne(customerDocOf(102L, "user_2", "Shanghai", "123567891234"));
        mongoCollection.updateOne(Filters.eq("cid", 103L), Updates.set("address", "Hangzhou"));
    }

    private void makeSecondPartChangeStreamEvents(MongoDatabase mongoDatabase, String collection) {
        MongoCollection<Document> mongoCollection = mongoDatabase.getCollection(collection);
        mongoCollection.updateOne(Filters.eq("cid", 1010L), Updates.set("address", "Hangzhou"));
        mongoCollection.insertMany(
                Arrays.asList(
                        customerDocOf(2001L, "user_22", "Shanghai", "123567891234"),
                        customerDocOf(2002L, "user_23", "Shanghai", "123567891234"),
                        customerDocOf(2003L, "user_24", "Shanghai", "123567891234")));
    }

    private Document customerDocOf(Long cid, String name, String address, String phoneNumber) {
        Document document = new Document();
        document.put("cid", cid);
        document.put("name", name);
        document.put("address", address);
        document.put("phone_number", phoneNumber);
        return document;
    }
}
