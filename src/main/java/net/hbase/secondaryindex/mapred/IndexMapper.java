package net.hbase.secondaryindex.mapred;

import java.io.IOException;
import java.util.*;

import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.Writable;

import net.hbase.secondaryindex.util.Const;

public class IndexMapper extends TableMapper<ImmutableBytesWritable, Writable> {

	private byte[] columnFamily;
	private byte[] columnQualifier;
	private boolean isBuildSingleIndex;

	private String column;
	private Map<String, Set<String>> colNameValSetrMap;

	@Override
	protected void setup(Context context) throws IOException,
			InterruptedException {
		// columnFamily = Bytes.toBytes(context.getConfiguration().get(
		// "conf.columnfamily"));
		// columnqualifier = Bytes.toBytes(context.getConfiguration().get(
		// "conf.columnqualifier"));
		column = context.getConfiguration().get(Const.HBASE_CONF_COLUMN_NAME);
		isBuildSingleIndex = context.getConfiguration().getBoolean(
				Const.HBASE_CONF_ISBUILDSINGLEINDEX_NAME, true);
	}

	@SuppressWarnings("rawtypes")
	@Override
	public void map(ImmutableBytesWritable row, Result columns, Context context)
			throws IOException {
		String value = null;
		// Map<String, String> colVal = new HashMap<String, String>();
		byte[] rowkey = row.get();
		byte[] cf = Const.COLUMN_FAMILY_CF1;
		byte[] qualifier = Const.COLUMN_RK;

		try {
			for (KeyValue kv : columns.list()) {
				value = Bytes.toStringBinary(kv.getValue());
				long ts = kv.getTimestamp();
				columnFamily = kv.getFamily();
				columnQualifier = kv.getQualifier();

				String columnName = Bytes.toString(columnFamily)
						+ Const.FAMILY_COLUMN_SEPARATOR
						+ Bytes.toString(columnQualifier);
				if (null != value && value.length() > 0) {
					Put put = new Put(Bytes.toBytes(columnName
							+ Const.ROWKEY_DEFAULT_SEPARATOR + value), ts);
					put.add(cf, qualifier, rowkey);
					context.write(row, put);
					if (!isBuildSingleIndex) {
						Set<String> colValSet = colNameValSetrMap
								.get(columnName);
						colValSet.add(value);
						colNameValSetrMap.put(columnName, colValSet);
					}
				}
			}

			/* build combined index */
			if (!isBuildSingleIndex) {
				// initial column name and values map
				String[] arr = column.split(",", -1);
				colNameValSetrMap = new HashMap<String, Set<String>>(arr.length);
				for (int i = 0; i < arr.length; i++) {
					colNameValSetrMap.put(arr[i], new HashSet<String>());
				}

				// remove empty columns
				Map<String, Set<String>> cleanedMap = removeEmptyEntry(colNameValSetrMap); // valid
				if (cleanedMap.size() > 1 && cleanedMap.size() < 4
						&& cleanedMap.size() <= arr.length) {
					// The existing columns of this rowkey is 3 and the input
					// 'column' is 3 too.
					if (cleanedMap.size() == 3) {
						Set<String> cn0 = cleanedMap.get(arr[0]);
						Set<String> cn1 = cleanedMap.get(arr[1]);
						Set<String> cn2 = cleanedMap.get(arr[2]);
						for (String v0 : cn0) {
							for (String v1 : cn1) {
								for (String v2 : cn2) {
									Vector<String> source = new Vector<String>();
									source.add(arr[0]
											+ Const.ROWKEY_DEFAULT_SEPARATOR
											+ v0);
									source.add(arr[1]
											+ Const.ROWKEY_DEFAULT_SEPARATOR
											+ v1);
									source.add(arr[2]
											+ Const.ROWKEY_DEFAULT_SEPARATOR
											+ v2);
									Vector<Vector> comb = Combination
											.getLowerLimitCombinations(source,
													2);
									if (null != comb && comb.size() > 0) {
										for (Vector v : comb) {
											String indexRowkey = v.toString()
													.replaceAll(", ", "_")
													.replaceAll("\\[", "")
													.replaceAll("\\]", "");
											Put put = new Put(
													Bytes.toBytes(indexRowkey));
											put.add(cf, qualifier, rowkey);
											context.write(row, put);
										}
									}
								}
							}
						}
						// The input 'column' is 2 or 3, and the existing
						// columns of this rowkey is 2.
					} else if (cleanedMap.size() == 2) {
						Set<String> cn0 = null;
						Set<String> cn1 = null;
						// arr convert to list, do not use Arrays.asList(), it
						// could not use remove().
						List<String> arrList = new ArrayList<String>();
						for (String s : arr) {
							arrList.add(s);
						}

						if (arr.length == 3) {
							String key = getKeyWithEmptyValue(colNameValSetrMap);
							arrList.remove(key);
							cn0 = cleanedMap.get(arrList.get(0));
							cn1 = cleanedMap.get(arrList.get(1));
						} else if (arr.length == 2) {
							cn0 = cleanedMap.get(arr[0]);
							cn1 = cleanedMap.get(arr[1]);
						}

						for (String v0 : cn0) {
							for (String v1 : cn1) {
								String indexRowkey = arrList.get(0)
										+ Const.ROWKEY_DEFAULT_SEPARATOR + v0
										+ Const.ROWKEY_DEFAULT_SEPARATOR
										+ arrList.get(1)
										+ Const.ROWKEY_DEFAULT_SEPARATOR + v1;
								Put put = new Put(Bytes.toBytes(indexRowkey));
								put.add(cf, qualifier, rowkey);
								context.write(row, put);
							}
						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("Error: " + e.getMessage() + ", Row: "
					+ Bytes.toString(row.get()) + ", Value: " + value);
		}
	}

	private Map<String, Set<String>> removeEmptyEntry(
			Map<String, Set<String>> map) {
		Map<String, Set<String>> rst = new HashMap<String, Set<String>>();
		if (null != map && map.size() > 0) {
			Set<Map.Entry<String, Set<String>>> set = map.entrySet();
			for (Iterator<Map.Entry<String, Set<String>>> it = set.iterator(); it
					.hasNext();) {
				Map.Entry<String, Set<String>> entry = (Map.Entry<String, Set<String>>) it
						.next();
				Set<String> value = entry.getValue();
				if (value.size() > 0)
					rst.put(entry.getKey(), value);
			}
		}
		return rst;
	}

	private String getKeyWithEmptyValue(Map<String, Set<String>> map) {
		String rst = null;
		if (null != map && map.size() > 0) {
			Set<Map.Entry<String, Set<String>>> set = map.entrySet();
			for (Iterator<Map.Entry<String, Set<String>>> it = set.iterator(); it
					.hasNext();) {
				Map.Entry<String, Set<String>> entry = (Map.Entry<String, Set<String>>) it
						.next();
				Set<String> value = entry.getValue();
				if (value.size() == 0)
					rst = entry.getKey();
			}
		}
		return rst;
	}

}
