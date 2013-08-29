package com.foundationdb.lucene;

import com.foundationdb.tuple.Tuple;
import org.apache.lucene.codecs.DocValuesConsumer;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfo.DocValuesType;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

//
// Writer
//
class FDBDocValuesConsumer extends DocValuesConsumer
{
    static final String BYTES = "bytes";
    static final String ORD = "ord";
    static final String DOC_TO_ORD = "doc_ord";

    // Asserts only
    private final Set<String> fieldsSeen = new HashSet<String>();

    private final FDBDirectory dir;
    private final Tuple segmentTuple;
    final int numDocs;


    public FDBDocValuesConsumer(SegmentWriteState state, String ext) throws IOException {
        //System.out.println("new consumer"); System.out.flush();
        dir = FDBDirectory.unwrapFDBDirectory(state.directory);
        segmentTuple = dir.subspace.add(state.segmentInfo.name).add(state.segmentSuffix).add(ext);
        numDocs = state.segmentInfo.getDocCount();
    }

    private boolean sawField(String field) {
        assert !fieldsSeen.contains(field) : "field \"" + field + "\" was added more than once during flush";
        fieldsSeen.add(field);
        return true;
    }

    //
    // type=numeric:
    // (dirTuple, segName, segSuffix, ext, fieldName, NUMERIC, docID) => (value0)
    //
    // type=sorted:
    // (dirTuple, segName, segSuffix, ext, fieldName, SORTED, "bytes", ordNum) => (bytes)
    // ...
    // (dirTuple, segName, segSuffix, ext, fieldName, SORTED, "ord", docID) => (ordNum)
    // ...
    //
    // type=sortedSet:
    // (dirTuple, segName, segSuffix, ext, fieldName, SORTED_SET, "bytes", ordNum) => (bytes)
    // ...
    // (dirTuple, segName, segSuffix, ext, fieldName, SORTED_SET, "doc_ord", docID0, ordNum0) => []
    // (dirTuple, segName, segSuffix, ext, fieldName, SORTED_SET, "doc_ord", docID0, ordNum1) => []
    // (dirTuple, segName, segSuffix, ext, fieldName, SORTED_SET, "doc_ord", docID1, ordNum0) => []
    // ...
    //

    @Override
    public void addNumericField(FieldInfo field, Iterable<Number> values) {
        assert sawField(field.name);
        assert (field.getDocValuesType() == DocValuesType.NUMERIC || field.getNormType() == DocValuesType.NUMERIC);

        Tuple fieldTuple = segmentTuple.add(field.name).add(DocValuesType.NUMERIC.ordinal());

        int numDocsWritten = 0;
        for(Number n : values) {
            //System.out.println("  write numeric: " + field.name + ", doc: " + numDocsWritten); System.out.flush();
            assert n instanceof Long : "Not long: " + n.getClass();
            dir.txn.set(fieldTuple.add(numDocsWritten).pack(), Tuple.from(n).pack());
            ++numDocsWritten;
        }

        assert numDocs == numDocsWritten : "numDocs=" + this.numDocs + " numDocsWritten=" + numDocsWritten;
    }

    @Override
    public void addBinaryField(FieldInfo field, Iterable<BytesRef> values) {
        assert sawField(field.name);
        assert field.getDocValuesType() == DocValuesType.BINARY;

        Tuple fieldTuple = segmentTuple.add(field.name).add(DocValuesType.BINARY.ordinal());

        int numDocsWritten = 0;
        for(BytesRef value : values) {
            //System.out.println("  write binary: " + field.name + ", doc: " + numDocsWritten); System.out.flush();
            dir.txn.set(fieldTuple.add(numDocsWritten).pack(), Tuple.from().add(FDBDirectory.copyRange(value)).pack());
            ++numDocsWritten;
        }

        assert numDocs == numDocsWritten;
    }

    @Override
    public void addSortedField(FieldInfo field,
                               Iterable<BytesRef> values,
                               Iterable<Number> docToOrd) throws IOException {
        assert sawField(field.name);
        assert field.getDocValuesType() == DocValuesType.SORTED;

        Tuple fieldTuple = segmentTuple.add(field.name).add(DocValuesType.SORTED.ordinal());

        {
            Tuple bytesTuple = fieldTuple.add(BYTES);
            int ordNum = 0;
            for(BytesRef value : values) {
                dir.txn.set(bytesTuple.add(ordNum).pack(), Tuple.from().add(FDBDirectory.copyRange(value)).pack());
                ++ordNum;
            }
        }

        {
            Tuple ordTuple = fieldTuple.add(ORD);
            int numDocsWritten = 0;
            for(Number ord : docToOrd) {
                //System.out.println("  write sorted: " + field.name + ", doc: " + numDocsWritten); System.out.flush();
                long value = ord.longValue();
                dir.txn.set(ordTuple.add(numDocsWritten).pack(), Tuple.from(value).pack());
                ++numDocsWritten;
            }

            assert numDocs == numDocsWritten : "numDocs=" + this.numDocs + " numDocsWritten=" + numDocsWritten;
        }
    }

    @Override
    public void addSortedSetField(FieldInfo field,
                                  Iterable<BytesRef> values,
                                  Iterable<Number> docToOrdCount,
                                  Iterable<Number> ords) throws IOException {
        assert sawField(field.name);
        assert field.getDocValuesType() == DocValuesType.SORTED_SET;

        Tuple fieldTuple = segmentTuple.add(field.name).add(DocValuesType.SORTED_SET.ordinal());

        {
            Tuple bytesTuple = fieldTuple.add(BYTES);
            int bytesWritten = 0;
            for(BytesRef value : values) {
                dir.txn.set(bytesTuple.add(bytesWritten).pack(), Tuple.from().add(FDBDirectory.copyRange(value)).pack());
                ++bytesWritten;
            }
        }

        Tuple docOrdTuple = fieldTuple.add(DOC_TO_ORD);
        int docID = 0;
        Iterator<Number> ordIt = ords.iterator();
        for(Number ordCount : docToOrdCount) {
            Tuple docOrdDocTuple = docOrdTuple.add(docID);
            for(int i = 0; i < ordCount.longValue(); ++i) {
                long ord = ordIt.next().longValue();
                dir.txn.set(docOrdDocTuple.add(ord).pack(), new byte[0]);
            }
            ++docID;
        }

        assert numDocs == docID : "numDocs=" + this.numDocs + " numDocsWritten=" + docID;
    }

    @Override
    public void close() throws IOException {
        // None
        assert !fieldsSeen.isEmpty();
    }
}
