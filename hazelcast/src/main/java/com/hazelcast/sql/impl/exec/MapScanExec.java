/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.sql.impl.exec;

import com.hazelcast.internal.util.Clock;
import com.hazelcast.internal.util.collection.PartitionIdSet;
import com.hazelcast.map.impl.PartitionContainer;
import com.hazelcast.map.impl.proxy.MapProxyImpl;
import com.hazelcast.map.impl.record.Record;
import com.hazelcast.map.impl.recordstore.RecordStore;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.query.impl.getters.Extractors;
import com.hazelcast.sql.impl.expression.Expression;
import com.hazelcast.sql.impl.row.EmptyRowBatch;
import com.hazelcast.sql.impl.row.HeapRow;
import com.hazelcast.sql.impl.row.Row;
import com.hazelcast.sql.impl.row.RowBatch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Executor for map scan.
 */
public class MapScanExec extends AbstractMapScanExec {
    /** Underlying map. */
    private final MapProxyImpl map;

    /** Partitions to be scanned. */
    private final PartitionIdSet parts;

    /** All rows fetched on first access. */
    private Collection<Row> rows;

    /** Iterator over rows. */
    private Iterator<Row> rowsIter;

    /** Current row. */
    private Row currentRow;

    public MapScanExec(
        MapProxyImpl map,
        PartitionIdSet parts,
        List<String> fieldNames,
        List<Integer> projects,
        Expression<Boolean> filter
    ) {
        super(map.getName(), fieldNames, projects, filter);

        this.map = map;
        this.parts = parts;
    }

    @SuppressWarnings("unchecked")
    @Override
    public IterationResult advance() {
        if (rows == null) {
            rows = new ArrayList<>();

            for (int i = 0; i < parts.getPartitionCount(); i++) {
                if (!parts.contains(i)) {
                    continue;
                }

                // Per-partition stuff.
                PartitionContainer partitionContainer = map.getMapServiceContext().getPartitionContainer(i);

                RecordStore recordStore = partitionContainer.getRecordStore(mapName);

                Iterator<Record> iterator = recordStore.loadAwareIterator(Clock.currentTimeMillis(), false);

                while (iterator.hasNext()) {
                    Record record = iterator.next();

                    Data keyData =  record.getKey();
                    Object valData = record.getValue();

                    Object key = serializationService.toObject(keyData);
                    Object val = valData instanceof Data ? serializationService.toObject(valData) : valData;

                    HeapRow row = prepareRow(key, val);

                    if (row != null) {
                        rows.add(row);
                    }
                }
            }

            rowsIter = rows.iterator();
        }

        if (rowsIter.hasNext()) {
            currentRow = rowsIter.next();

            return IterationResult.FETCHED;
        } else {
            currentRow = null;

            return IterationResult.FETCHED_DONE;
        }
    }

    @Override
    public RowBatch currentBatch() {
        return currentRow != null ? currentRow : EmptyRowBatch.INSTANCE;
    }

    @Override
    protected void reset0() {
        rows = null;
        rowsIter = null;
        currentRow = null;
    }

    @Override
    protected Extractors createExtractors() {
        return map.getMapServiceContext().getExtractors(mapName);
    }

    @Override
    protected String normalizePath(String path) {
        return map.normalizeAttributePath(path);
    }
}