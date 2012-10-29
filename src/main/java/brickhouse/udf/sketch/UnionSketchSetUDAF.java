package brickhouse.udf.sketch;

/**
 *  UDAF to collect the union of multiple sketch sets
 *    and return a sketch representing the union of all 
 *    the sketches.
 * 
 */

import java.util.List;
import java.util.Map;

import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.udf.generic.AbstractGenericUDAFResolver;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDAFEvaluator;
import org.apache.hadoop.hive.serde2.objectinspector.ListObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.MapObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector.PrimitiveCategory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.LongObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.StringObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.log4j.Logger;

import brickhouse.analytics.uniques.SketchSet;

@Description(name="union_sketch",
    value = "_FUNC_(x) - Constructs a sketch set to estimate reach for large values by collecting multiple sketches "
)
public class UnionSketchSetUDAF extends AbstractGenericUDAFResolver {
  private static final Logger LOG = Logger.getLogger(UnionSketchSetUDAF.class);
  public static int DEFAULT_SKETCH_SET_SIZE =  5000;


  @Override
  public GenericUDAFEvaluator getEvaluator(TypeInfo[] parameters)
      throws SemanticException {
	  ///  XXX if Type is array, then do merge sketching instead
	  /// XXX  Just make one UDAF , called "sketch_set" ???
	  /// TODO XXX Which is more useful???
    return new MergeSketchSetUDAFEvaluator();
  }


  public static class MergeSketchSetUDAFEvaluator extends GenericUDAFEvaluator {
    // For PARTIAL1 and COMPLETE: ObjectInspectors for original data
	  private StringObjectInspector inputStrOI;
	  private ListObjectInspector inputListOI;
	  private MapObjectInspector partialMapOI;
	  private LongObjectInspector partialMapHashOI;
	  private StringObjectInspector partialMapStrOI;


    static class SketchSetBuffer implements AggregationBuffer {
    	private SketchSet sketchSet;
    	
     
    	public void init() {
    		if( sketchSet == null) {
    			sketchSet = new SketchSet( DEFAULT_SKETCH_SET_SIZE);
    		} else {
    			sketchSet.clear();
    		}
    	}
    	public void reset() {
    		if( sketchSet == null) {
    			sketchSet = new SketchSet( DEFAULT_SKETCH_SET_SIZE);
    		} else {
    			sketchSet.clear();
    		}
    	}
    	
    	
    	public List<String> getSketchItems() {
           return sketchSet.getMinHashItems();
    	}
    	
        public Map<Long,String> getPartialMap() {
    	   return  sketchSet.getHashItemMap();
        }
        
        public void addItem( String str) {
           sketchSet.addItem( str) ;
        }
        public void addHash( Long hash, String str) {
        	sketchSet.addHashItem( hash, str );
        }
      

    }

    public ObjectInspector init(Mode m, ObjectInspector[] parameters)
        throws HiveException {
      super.init(m, parameters);
      LOG.info(" SketchSetUDAF.init() - Mode= " + m.name() );
      for(int i=0; i<parameters.length; ++i) {
        LOG.info(" ObjectInspector[ "+ i + " ] = " + parameters[0]);
      }
      /// 
      if (m == Mode.PARTIAL1 || m == Mode.COMPLETE) {
    	  //// iterate() gets called.. list of  string is passed in
    	  if( parameters[0].getCategory() == Category.LIST) {
    	    this.inputListOI = (ListObjectInspector) parameters[0];
    	    if(((PrimitiveObjectInspector)this.inputListOI.getListElementObjectInspector()).getPrimitiveCategory() == PrimitiveCategory.STRING ) {
    	    	this.inputStrOI = (StringObjectInspector)this.inputListOI.getListElementObjectInspector();
    	    }
    	  } else {
    		  throw new HiveException(" union_sketch expecting list of strings as arg");
    	  }
      } else { /// Mode m == Mode.PARTIAL2 || m == Mode.FINAL
    	   /// merge() gets called ... map is passed in ..
    	  this.partialMapOI = (MapObjectInspector) parameters[0];
    	  this.partialMapHashOI = (LongObjectInspector) partialMapOI.getMapKeyObjectInspector();
    	  this.partialMapStrOI = (StringObjectInspector) partialMapOI.getMapValueObjectInspector();
        		 
      } 
      /// The intermediate result is a map of hashes and strings,
      /// The final result is an array of strings
      if( m == Mode.FINAL || m == Mode.COMPLETE) {
    	  /// for final result
         return ObjectInspectorFactory.getStandardListObjectInspector(
              PrimitiveObjectInspectorFactory.javaStringObjectInspector );
      } else { /// m == Mode.PARTIAL1 || m == Mode.PARTIAL2 
         return ObjectInspectorFactory.getStandardMapObjectInspector(
        		 PrimitiveObjectInspectorFactory.javaLongObjectInspector,
        		 PrimitiveObjectInspectorFactory.javaStringObjectInspector
        		 );
      }
    }

    @Override
    public AggregationBuffer getNewAggregationBuffer() throws HiveException {
      SketchSetBuffer buff= new SketchSetBuffer();
      buff.init();
      return buff;
    }

    @Override
    public void iterate(AggregationBuffer agg, Object[] parameters)
        throws HiveException {
      Object listStrObj = parameters[0];

      if (listStrObj != null) {
    	  List listStr = inputListOI.getList(listStrObj);
    	  for( Object strObj : listStr ) {
    	     String str = inputStrOI.getPrimitiveJavaObject( strObj);
             SketchSetBuffer myagg = (SketchSetBuffer) agg;
             myagg.sketchSet.addItem( str);
    	  }
      }
    }

    @Override
    public void merge(AggregationBuffer agg, Object partial)
        throws HiveException {
    	/// Partial is going to be a map of strings and hashes 
        SketchSetBuffer myagg = (SketchSetBuffer) agg;
        
        Map<Object,Object> partialResult = (Map<Object,Object>)  this.partialMapOI.getMap(partial);
        for( Map.Entry entry : partialResult.entrySet()) {
        	Long hash = this.partialMapHashOI.get( entry.getKey());
        	String item = partialMapStrOI.getPrimitiveJavaObject( entry.getValue());
        	myagg.addHash(hash, item);
        }
    }

    @Override
    public void reset(AggregationBuffer buff) throws HiveException {
      SketchSetBuffer sketchBuff = (SketchSetBuffer) buff;
      sketchBuff.reset();
    }

    @Override
    public Object terminate(AggregationBuffer agg) throws HiveException {
      SketchSetBuffer myagg = (SketchSetBuffer) agg;
      return myagg.getSketchItems();
    }

    @Override
    public Object terminatePartial(AggregationBuffer agg) throws HiveException {
    	SketchSetBuffer myagg = (SketchSetBuffer)agg;
    	return myagg.getPartialMap();
    }
  }


}