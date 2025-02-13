/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.performanceanalyzer.rca.framework.api.summaries;


import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.GeneratedMessageV3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.exception.DataTypeException;
import org.jooq.impl.DSL;
import org.opensearch.performanceanalyzer.grpc.FlowUnitMessage;
import org.opensearch.performanceanalyzer.rca.framework.api.persist.JooqFieldValue;
import org.opensearch.performanceanalyzer.rca.framework.core.GenericSummary;

/**
 * HotClusterSummary is a cluster level summary. It collects and aggregates node summaries from each
 * data nodes and additional info will be added by cluster_manager. This type of summary is created
 * by cluster level RCAs which only run on elected cluster_manager.
 *
 * <p>This object is persisted in SQLite table Table name : HotClusterSummary
 *
 * <p>schema : | ID(primary key) | Number of nodes | unhealthy nodes | ID in FlowUnit(foreign key) |
 * 1 | 5 | 1 | 5
 */
public class HotClusterSummary extends GenericSummary {

    public static final String HOT_CLUSTER_SUMMARY_TABLE = HotClusterSummary.class.getSimpleName();
    private static final Logger LOG = LogManager.getLogger(HotClusterSummary.class);
    private int numOfNodes;
    private int numOfUnhealthyNodes;
    private List<HotNodeSummary> hotNodeSummaryList;

    public HotClusterSummary(int numOfNodes, int numOfUnhealthyNodes) {
        super();
        this.numOfNodes = numOfNodes;
        this.numOfUnhealthyNodes = numOfUnhealthyNodes;
        this.hotNodeSummaryList = new ArrayList<>();
    }

    /**
     * HotClusterSummary is supposed to be created on elected cluster_manager node only. and we do
     * not expect it to be sent via gRPC. Return null in all the methods below. and we should not
     * define the gRPC message wrapper for this summary class in protocol buf.
     */
    @Override
    public GeneratedMessageV3 buildSummaryMessage() {
        return null;
    }

    @Override
    public void buildSummaryMessageAndAddToFlowUnit(FlowUnitMessage.Builder messageBuilder) {}

    public int getNumOfNodes() {
        return numOfNodes;
    }

    public int getNumOfUnhealthyNodes() {
        return numOfUnhealthyNodes;
    }

    @NonNull
    public List<HotNodeSummary> getHotNodeSummaryList() {
        return hotNodeSummaryList;
    }

    public void appendNestedSummary(HotNodeSummary summary) {
        hotNodeSummaryList.add(summary);
    }

    @Override
    public String toString() {
        return this.numOfNodes + " " + this.numOfUnhealthyNodes + " " + getNestedSummaryList();
    }

    @Override
    public String getTableName() {
        return HotClusterSummary.HOT_CLUSTER_SUMMARY_TABLE;
    }

    @Override
    public List<Field<?>> getSqlSchema() {
        List<Field<?>> schema = new ArrayList<>();
        schema.add(ClusterSummaryField.NUM_OF_NODES_FIELD.getField());
        schema.add(ClusterSummaryField.NUM_OF_UNHEALTHY_NODES_FIELD.getField());
        return schema;
    }

    @Override
    public List<Object> getSqlValue() {
        List<Object> value = new ArrayList<>();
        value.add(Integer.valueOf(this.numOfNodes));
        value.add(Integer.valueOf(this.numOfUnhealthyNodes));
        return value;
    }

    /**
     * Convert this summary object to JsonElement
     *
     * @return JsonElement
     */
    @Override
    public JsonElement toJson() {
        JsonObject summaryObj = new JsonObject();
        summaryObj.addProperty(SQL_SCHEMA_CONSTANTS.NUM_OF_NODES_COL_NAME, this.numOfNodes);
        summaryObj.addProperty(
                SQL_SCHEMA_CONSTANTS.NUM_OF_UNHEALTHY_NODES_COL_NAME, this.numOfUnhealthyNodes);
        if (!getNestedSummaryList().isEmpty()) {
            String tableName = getNestedSummaryList().get(0).getTableName();
            summaryObj.add(tableName, this.nestedSummaryListToJson());
        }
        return summaryObj;
    }

    @Override
    public List<GenericSummary> getNestedSummaryList() {
        return new ArrayList<>(hotNodeSummaryList);
    }

    @Override
    public GenericSummary buildNestedSummary(String summaryTable, Record record)
            throws IllegalArgumentException {
        if (summaryTable.equals(HotNodeSummary.HOT_NODE_SUMMARY_TABLE)) {
            HotNodeSummary hotNodeSummary = HotNodeSummary.buildSummary(record);
            if (hotNodeSummary != null) {
                hotNodeSummaryList.add(hotNodeSummary);
            }
            return hotNodeSummary;
        } else {
            throw new IllegalArgumentException(
                    summaryTable + " does not belong to the nested summaries of " + getTableName());
        }
    }

    @Override
    public List<String> getNestedSummaryTables() {
        return Collections.unmodifiableList(
                Collections.singletonList(HotNodeSummary.HOT_NODE_SUMMARY_TABLE));
    }

    public static class SQL_SCHEMA_CONSTANTS {

        public static final String NUM_OF_NODES_COL_NAME = "number_of_nodes";
        public static final String NUM_OF_UNHEALTHY_NODES_COL_NAME = "number_of_unhealthy_nodes";
    }

    /** Cluster summary SQL fields */
    public enum ClusterSummaryField implements JooqFieldValue {
        NUM_OF_NODES_FIELD(SQL_SCHEMA_CONSTANTS.NUM_OF_NODES_COL_NAME, Integer.class),
        NUM_OF_UNHEALTHY_NODES_FIELD(
                SQL_SCHEMA_CONSTANTS.NUM_OF_UNHEALTHY_NODES_COL_NAME, Integer.class);

        private String name;
        private Class<?> clazz;

        ClusterSummaryField(final String name, Class<?> clazz) {
            this.name = name;
            this.clazz = clazz;
        }

        @Override
        public Field<?> getField() {
            return DSL.field(DSL.name(this.name), this.clazz);
        }

        @Override
        public String getName() {
            return this.name;
        }
    }

    /**
     * parse SQL query result and fill the result into summary obj.
     *
     * @param record SQLite record
     * @return whether parsing is successful or not
     */
    @Nullable
    public static HotClusterSummary buildSummary(Record record) {
        if (record == null) {
            return null;
        }
        HotClusterSummary summary = null;
        try {
            Integer numOfNodes =
                    record.get(ClusterSummaryField.NUM_OF_NODES_FIELD.getField(), Integer.class);
            Integer numOfUnhealthyNodes =
                    record.get(
                            ClusterSummaryField.NUM_OF_UNHEALTHY_NODES_FIELD.getField(),
                            Integer.class);
            if (numOfNodes == null || numOfUnhealthyNodes == null) {
                LOG.warn(
                        "read null object from SQL, numOfNodes: {}, numOfUnhealthyNodes: {}",
                        numOfNodes,
                        numOfUnhealthyNodes);
                return null;
            }
            summary = new HotClusterSummary(numOfNodes, numOfUnhealthyNodes);
        } catch (IllegalArgumentException ie) {
            LOG.error("Some fields might not be found in record, cause : {}", ie.getMessage());
        } catch (DataTypeException de) {
            LOG.error("Fails to convert data type");
        }
        return summary;
    }
}
