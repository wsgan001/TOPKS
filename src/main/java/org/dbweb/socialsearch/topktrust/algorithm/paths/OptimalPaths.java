package org.dbweb.socialsearch.topktrust.algorithm.paths;

import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import java.util.Map;
import java.util.Set;

import org.dbweb.Arcomem.Integration.LoadIntoMemory;
import org.dbweb.socialsearch.general.connection.DBConnection;
import org.dbweb.socialsearch.topktrust.algorithm.functions.PathCompositionFunction;
import org.dbweb.socialsearch.topktrust.algorithm.functions.PathMinimum;
import org.dbweb.socialsearch.topktrust.algorithm.functions.PathMultiplication;
import org.dbweb.socialsearch.topktrust.algorithm.functions.PathPow;
import org.dbweb.socialsearch.topktrust.datastructure.UserEntry;
import org.dbweb.socialsearch.topktrust.datastructure.UserLink;
import org.dbweb.socialsearch.topktrust.datastructure.general.FibonacciHeap;
import org.dbweb.socialsearch.topktrust.datastructure.general.FibonacciHeapNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OptimalPaths {
	protected String pathToDistributions = System.getProperty("user.dir")+"/distr_gen/";

	protected static String sqlGetNetworkLinksTemplate = "select user1, user2, weight from %s order by user1 asc, weight desc";
	
	private static Logger log = LoggerFactory.getLogger(OptimalPaths.class); 
	
	private Map<Integer,List<UserLink<Integer,Float>>> network;
	private List<UserEntry<Float>> friends;
	private List<Float> values;
	private Set<Integer> done;
	private Map<Integer,FibonacciHeapNode<Integer>> nodes;
	private FibonacciHeap<Integer> prioQueue;
	private float max_pos_val;
	private Connection connection;
	private PathCompositionFunction distFunc;
	
	private PathCompositionFunction[] func = {new PathMinimum(), new PathMultiplication(), new PathPow()};
	
	private String networkTable;
	int total_sum = 0;
	int total_users = 0;
	private boolean heap = true;
	
	public OptimalPaths(String networkTable, DBConnection connection, boolean heap, ArrayList<Float> values, double coeff){
		this.networkTable = networkTable;
		if (connection != null)
			this.connection = connection.DBConnect();
		else
			this.connection = null;
		this.values = values;
		this.heap = heap;
		this.func[2]=new PathPow(coeff);
		LoadIntoMemory.loadData(this.connection); //DEBUG PURPOSE
	}
	
	public void setValues(List<Float> values){
		this.values = values;
	}
	
	public void setDistFunc( PathCompositionFunction distFunc){
		this.distFunc = distFunc;
	}
	
	public void computePaths(ArrayList<String> seekers){		
		for(String seeker:seekers)
			for(PathCompositionFunction fnc:func){
				this.distFunc = fnc;
				calculateOptimalPaths(seeker);
			}	
	}
	
	public UserEntry<Float> initiateHeapCalculation(int seeker, List<String> query) throws SQLException {
    	
    	total_users = 0;
    	this.max_pos_val = 1.0f;
		this.friends = new ArrayList<UserEntry<Float>>();    	
    	done = new HashSet<Integer>();
    	prioQueue = new FibonacciHeap<Integer>();
    	FibonacciHeapNode<Integer> seekerUser = new FibonacciHeapNode<Integer>(seeker,new Float(1.0));
    	nodes = new HashMap<Integer,FibonacciHeapNode<Integer>>();
    	done.add(seeker);
    	nodes.put(seeker, seekerUser);
    	prioQueue.insert(seekerUser,new Float(1.0));

    	FibonacciHeapNode<Integer> currentUser = prioQueue.removeMin();
    	float userWeight = (float)currentUser.getKey();

    	this.total_sum += userWeight;    
    	UserEntry<Float> retVal = new UserEntry<Float>(currentUser.getData(),(float)currentUser.getKey());
    	friends.add(retVal);
    	
    	return retVal;
	}
	
	public UserEntry<Float> advanceFriendsList(UserEntry<Float> currentUser) throws SQLException{
    	FibonacciHeapNode<Integer> currUser = null;
    	if(currentUser!=null)
    		if(nodes.containsKey(currentUser.getEntryId()))
    			currUser = nodes.get(currentUser.getEntryId());
    	if(this.heap&&currentUser!=null&&currUser!=null){//if we don't have any new values -- calculate a new heap
    		calculateHeap(currUser);
    		if(prioQueue.size()>0){
    			currUser = prioQueue.removeMin();
    			total_users++;
    			UserEntry<Float> retVal = new UserEntry<Float>(currUser.getData(),(float)(1.0f/currUser.getKey())); 
    			friends.add(new UserEntry<Float>(currUser.getData(),(float)(1.0f/currUser.getKey())));    			
    			values.add(retVal.getDist());
        		return retVal;
    		}
    		else
    			return null;
    	}
    	return null;
    }
    
	
	private void calculateOptimalPaths(String seeker){
		log.info("\toptimal paths for {} and function {}",seeker,distFunc.toString());
    	this.max_pos_val = 1.0f;
    	int sk = Integer.parseInt(seeker);
		this.friends = new ArrayList<UserEntry<Float>>();    	
    	done = new HashSet<Integer>();
    	prioQueue = new FibonacciHeap<Integer>();
    	FibonacciHeapNode<Integer> seekerUser = new FibonacciHeapNode<Integer>(sk,new Float(1.0));
    	nodes = new HashMap<Integer,FibonacciHeapNode<Integer>>();
    	done.add(sk);
    	prioQueue.insert(seekerUser,new Float(1.0));

    	FibonacciHeapNode<Integer> currentUser = prioQueue.removeMin();
    	double userWeight = currentUser.getKey();

    	this.total_sum += userWeight;    	
    	friends.add(new UserEntry<Float>(currentUser.getData(),(float)currentUser.getKey()));

    	while(userWeight!=Float.NEGATIVE_INFINITY){
    		//Getting neighbors
    		calculateHeap(currentUser);       		
    		if(prioQueue.size()>0){
    			currentUser = prioQueue.removeMin();
    			done.add(currentUser.getData());
    			userWeight = currentUser.getKey();
    			this.total_sum += userWeight;
    			friends.add(new UserEntry<Float>(currentUser.getData(),(float)(1.0f/currentUser.getKey())));
    		}
    		else
    			userWeight = Float.NEGATIVE_INFINITY;
    	}
    	writeStatistics(seeker);
    }
	
	private void calculateHeap(FibonacciHeapNode<Integer> currentUser){
    	boolean foundFirst = false;
    	List<UserLink<Integer,Float>> neighbl = getNeighbList(currentUser.getData());
    	if(neighbl!=null){
    		for(UserLink<Integer,Float> neighb:neighbl){
    			int neighbourId = neighb.getRecipient();
    			float weight = neighb.getWeight();
    			FibonacciHeapNode<Integer> neighbour = new FibonacciHeapNode<Integer>(neighbourId,Float.POSITIVE_INFINITY);
    			if(!done.contains(neighbourId)){
        			if(!nodes.containsKey(neighbourId)){
        				nodes.put(neighbourId, neighbour);
        				prioQueue.insert(neighbour, Float.POSITIVE_INFINITY);
        			}
        			else{        				        			
        				neighbour = nodes.get(neighbourId);
        			}
        			relax(currentUser, neighbour, new Float(weight)); 
    			}
    		}
    	}
    }
	
	private void relaxMax(UserEntry<Float> u, UserEntry<Float> v, Float w){
        Comparable result = this.distFunc.compute(max_pos_val*u.getDist(), w);
        if(result.compareTo(max_pos_val*v.getDist())>0){
            v.setDist((Float)result/(Float)max_pos_val);
            v.setPred(u);
        }
    }
	
	private void relax(FibonacciHeapNode<Integer> u, FibonacciHeapNode<Integer> v, Float w){
		float val_u = (float)u.getKey();
		val_u = (val_u==Float.POSITIVE_INFINITY)?0:max_pos_val/(val_u);
		float val_v = (float)v.getKey();
		val_v = (val_v==Float.POSITIVE_INFINITY)?0:max_pos_val/(val_v);
        Comparable result = this.distFunc.compute(val_u, w);
        if(result.compareTo(val_v)>0){
        	prioQueue.decreaseKey(v, (Float)max_pos_val/(Float)result);
        }
    }

	private List<UserLink<Integer,Float>> getNeighbList(int user){

		if(heap){
			Network net = Network.getInstance(connection);
			network = net.getNetwork(networkTable);
			if(this.network.containsKey(user))
				return this.network.get(user);
			else
				return null;
		}
		return null;
	}

	private void writeStatistics(String seeker){
		FileWriter fileCSV;
		try {
			fileCSV = new FileWriter(pathToDistributions+"dist_"+seeker+"_"+this.networkTable+"_"+this.distFunc.toString()+".csv");			
			for(UserEntry<Float> usr:friends)
				fileCSV.write(String.format(Locale.US,"%s,%s,%s,%.10f\n", this.distFunc.toString(), seeker, usr.getEntryId(), usr.getDist()));
			fileCSV.close();
		} 
		catch (IOException e) {
			log.error(e.getMessage());
		}
	}
	
	public int getTotUsers(){
		return this.total_users;
	}
}