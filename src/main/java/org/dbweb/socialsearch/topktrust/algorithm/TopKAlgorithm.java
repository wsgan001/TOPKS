/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.dbweb.socialsearch.topktrust.algorithm;

import java.util.Random;
import org.apache.commons.collections4.trie.PatriciaTrie;
import org.dbweb.Arcomem.datastructures.BasicSearchResult;
import org.dbweb.socialsearch.general.connection.DBConnection;
import org.dbweb.socialsearch.shared.Methods;
import org.dbweb.socialsearch.shared.Params;
import org.dbweb.socialsearch.topktrust.algorithm.functions.PathCompositionFunction;
import org.dbweb.socialsearch.topktrust.algorithm.paths.LandmarkPathsComputing;
import org.dbweb.socialsearch.topktrust.algorithm.paths.OptimalPaths;
import org.dbweb.socialsearch.topktrust.algorithm.score.Score;
import org.dbweb.socialsearch.topktrust.datastructure.DataDistribution;
import org.dbweb.socialsearch.topktrust.datastructure.DataHistogram;
import org.dbweb.socialsearch.topktrust.datastructure.Item;
import org.dbweb.socialsearch.topktrust.datastructure.ItemList;
import org.dbweb.socialsearch.topktrust.datastructure.UserEntry;
import org.dbweb.socialsearch.topktrust.datastructure.comparators.MinScoreItemComparator;
import org.dbweb.socialsearch.topktrust.datastructure.views.UserView;
import org.dbweb.socialsearch.topktrust.datastructure.views.ViewScore;
import org.dbweb.completion.trie.RadixTreeImpl;
import org.dbweb.completion.trie.RadixTreeNode;

import gnu.trove.iterator.TLongIterator;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TLongSet;
import gnu.trove.set.hash.THashSet;
import gnu.trove.set.hash.TLongHashSet;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 *
 * @author Silviu & Paul
 */
public class TopKAlgorithm {

	//debug purpose
	public ArrayList<Integer> visitedNodes;

	protected static double viewDistanceThreshold = 0.5;

	protected static String sqlGetDistributionTemplate = "select mean, var from stats_%s where \"user\"=? and func=?";
	protected static String sqlGetHistogramTemplate = "select bucket, num from hist_%s where \"user\"=? and func=? order by bucket asc";
	protected static String sqlGetTagFrequency = "select num from tagfreq where tag=?";
	protected static String sqlGetDocumentTf = "select num from docs where item=? and tag=?";
	protected static String sqlGetDocsListByTag = "select item,num from docs where tag=? order by num desc";
	protected static String sqlGetAllDocumentsTemplate = "select * from %s where tag in (";
	protected static String sqlGetDifferentTags = "SELECT distinct tag FROM %s";

	protected String sqlGetAllDocuments;
	protected String networkTable;
	protected String tagTable;
	protected Connection connection;

	protected ItemList candidates;

	public ItemList getPubCandids() {
		return this.candidates;
	}

	protected TIntObjectMap<PatriciaTrie<TLongSet>> userSpaces;
	protected RadixTreeImpl tag_idf;
	protected List<Float> values;
	protected Map<String,Integer> topValueQuery;
	protected Map<String, Integer> positions;
	protected Map<String,Float> userWeights;
	protected Map<String,Integer> tagFreqs;
	protected Map<String,ArrayList<UserView>> userviews;
	protected Map<String,HashSet<String>> unknown_tf;
	protected List<Integer> vst;
	protected Set<Integer> skr;
	protected Map<String, Long> topItemQuery;
	protected List<String> dictionary;
	protected PatriciaTrie<String> dictionaryTrie;
	protected RadixTreeImpl completionTrie; // Completion trie

	protected int[] pos;
	protected int seeker;

	protected float userWeight;
	protected UserEntry<Float> currentUser;

	protected DBConnection dbConnection;

	protected PathCompositionFunction distFunc;    

	protected boolean terminationCondition;

	protected long time_loop;

	protected int total_documents_social;
	protected int total_documents_asocial;
	protected int total_users;
	protected int total_rnd;
	protected int total_topk_changes;
	protected int total_conforming_lists;

	protected int number_documents;
	protected int number_users;

	protected float partial_sum = 0;
	protected float total_sum = 0;

	protected float alpha = 0;

	protected int total_lists_social;

	//amine
	protected String newXMLResults="", newBucketResults="", newXMLStats="";

	BasicSearchResult resultList=new BasicSearchResult();

	public BasicSearchResult getResultList() {
		resultList.sortItems();
		return resultList;
	}

	public void setAlpha(float alpha) {
		this.alpha = alpha;
	}

	protected int approxMethod;
	protected float max_pos_val;

	private OptimalPaths optpath;
	private LandmarkPathsComputing landmark;
	private DataDistribution d_distr;
	private DataHistogram d_hist;
	private ViewTransformer viewTransformer;
	private Score score;
	private double error;

	private double bestScoreEstim = Double.POSITIVE_INFINITY; 
	//debug purpose
	public double bestscore;

	private boolean docs_inserted;
	private boolean finished;

	private boolean needUnseen = true;
	private Set<String> guaranteed;
	private Set<String> possible;

	// NEW
	private Map<String, List<DocumentNumTag>> invertedLists;
	private int nbNeighbour;
	private List<Integer> queryNbNeighbour;

	private int numloops = 0;
	private int skippedTests; // Number of loops before testing the exit condition
	private int nVisited;
    private Random random;

	public TopKAlgorithm(DBConnection dbConnection, String tagTable, String networkTable, int method, Score itemScore, float scoreAlpha, PathCompositionFunction distFunc, OptimalPaths optPathClass, double error) throws SQLException {
		this.distFunc = distFunc;
		this.dbConnection = dbConnection;
		this.networkTable = networkTable;
		this.tagTable = tagTable;
		this.alpha = scoreAlpha;
		this.approxMethod = method;
		this.optpath = optPathClass;
		this.error = error;
		this.score = itemScore;
		this.skippedTests = 10000;
        this.random = new Random();

		long time_before_loading = System.currentTimeMillis();
		if (dbConnection != null)
			this.dbLoadingInMemory();
		else {
			try {
				this.fileLoadingInMemory();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		long time_after_loading = System.currentTimeMillis();
		System.out.println("File loading in "+(float)(time_after_loading-time_before_loading) / 1000 +" sec...");
	}

	public TopKAlgorithm(DBConnection dbConnection, String tagTable, String networkTable, int method, Score itemScore, float scoreAlpha, PathCompositionFunction distFunc, OptimalPaths optPathClass, double error, int number_documents, int number_users) {
		this.distFunc = distFunc;
		this.dbConnection = dbConnection;
		this.networkTable = networkTable;
		this.tagTable = tagTable;
		this.alpha = scoreAlpha;
		this.approxMethod = method;
		this.optpath = optPathClass;
		this.error = error;
		this.score = itemScore;
		this.number_documents = Params.number_documents;
		this.number_users = Params.number_users;
	}


	/**
	 * Main call from TopKAlgorithm class, call this after building a new object to run algorithm
	 * This query method must be called for the FIRST prefix, when iterating to the next letter, use
	 * the method executeQueryPlusLetter
	 * @param seeker
	 * @param query
	 * @param k
	 * @return
	 * @throws SQLException
	 */
	public int executeQuery(String seeker, List<String> query, int k, int t, boolean newQuery, int nVisited) throws SQLException {
		this.nVisited = nVisited;
		//System.out.println(query.toString()+", "+seeker);
		this.max_pos_val = 1.0f;
		this.d_distr = null;
		this.d_hist = null;
		this.total_users = 0;
		this.total_rnd = 0;
		this.seeker = Integer.parseInt(seeker);

		values = new ArrayList<Float>();
		unknown_tf = new HashMap<String,HashSet<String>>();
		for(String tag:query)
			unknown_tf.put(tag, new HashSet<String>());
		this.optpath.setValues(values);
		this.optpath.setDistFunc(distFunc);
		if((this.approxMethod&Methods.MET_APPR_LAND)==Methods.MET_APPR_LAND){
			landmark.setSeeker(this.seeker);
			landmark.setPathFunction(this.distFunc);
			currentUser = new UserEntry<Float>(this.seeker,1.0f);
		}
		else{
			currentUser = optpath.initiateHeapCalculation(this.seeker, query);
		}

		userWeight = 1.0f;
		terminationCondition = false;
		PreparedStatement ps;
		ResultSet result;
		if (newQuery) {
			topValueQuery = new HashMap<String, Integer>();
			topItemQuery = new HashMap<String, Long>();
			userWeights = new HashMap<String, Float>();
		}
		pos = new int[query.size()];

		String tag = query.get(query.size()-1);
		int index = 0;
		boolean exact = false;
		pos[index]=0;
		topItemQuery.put(tag, this.invertedLists.get(completionTrie.searchPrefix(tag, exact).getBestDescendant().getWord()).get(0).getDocId());
		topValueQuery.put(tag, (int)completionTrie.searchPrefix(tag, false).getValue());
		index++;
		userWeights.put(tag, userWeight);

		if((this.approxMethod&Methods.MET_APPR_MVAR)==Methods.MET_APPR_MVAR){
			String sqlGetDistribution = String.format(sqlGetDistributionTemplate, this.networkTable);
			ps = connection.prepareStatement(sqlGetDistribution);
			ps.setInt(1, Integer.parseInt(seeker));
			ps.setString(2, this.distFunc.toString());
			result = ps.executeQuery();
			if(result.next()){
				double mean = result.getDouble(1);
				double variance = result.getDouble(2);
				this.d_distr = new DataDistribution(mean, variance, Params.number_users, query);
			}
		}
		if((this.approxMethod&Methods.MET_APPR_HIST)==Methods.MET_APPR_HIST){
			String sqlGetHistogram = String.format(sqlGetHistogramTemplate, this.networkTable);
			ps = connection.prepareStatement(sqlGetHistogram);
			ps.setInt(1, Integer.parseInt(seeker));
			ps.setString(2, this.distFunc.toString());
			result = ps.executeQuery();
			ArrayList<Integer> hist = new ArrayList<Integer>();
			while(result.next()){
				int num = result.getInt(2);
				hist.add(num);
			}
			this.d_hist = new DataHistogram(Params.number_users, hist);
		}
		
		this.nbNeighbour = 0;
		if (newQuery) {
			this.queryNbNeighbour = new ArrayList<Integer>();
			Comparator comparator = new MinScoreItemComparator();
			candidates = new ItemList(comparator, this.score, this.d_distr, this.d_hist, this.error);
			candidates.setContribs(query, completionTrie);
		}
		else {
			candidates.cleanForNewWord(query, this.tag_idf, completionTrie, this.approxMethod);
		}
		
		this.queryNbNeighbour.add(0);

		total_users = 0;        
		total_lists_social = 0;
		total_documents_social = 0;
		total_documents_asocial = 0;
		total_topk_changes = 0;
		total_conforming_lists = 0;

		//long time0 = System.currentTimeMillis();
		mainLoop(k, seeker, query, t); /* MAIN ALGORITHM */
		//long time1 = System.currentTimeMillis();
		//System.out.println("Only mainLoop : "+(time1-time0)/1000+"sec.");

		return 0;
	}

	/**
	 * After answering a query session (initial prefix and possible completions), we need to
	 * reinitialize the trie and go back to the initial position of the tries.
	 * @param prefix
	 */
	public void reinitialize(String[] query, int length) { // TO CHECK
		String prefix = "";
		for (String keyword: query) {
			prefix = keyword.substring(0, length);
			SortedMap<String, String> completions = this.dictionaryTrie.prefixMap(prefix);
			Iterator<Entry<String, String>> iterator = completions.entrySet().iterator();
			Entry<String, String> currentEntry = null;
			userWeight = 1;
			while(iterator.hasNext()){
				currentEntry = iterator.next();
				String completion = currentEntry.getKey();
				if (positions.get(completion) == 0) {
					continue;
				}
				positions.put(completion, 0);
				DocumentNumTag firstDoc = this.invertedLists.get(completion).get(0);
				RadixTreeNode current_best_leaf = completionTrie.searchPrefix(completion, false).getBestDescendant();
				current_best_leaf.updatePreviousBestValue(firstDoc.getNum());
			}
		}
	}

	/**
	 * When a query with prefix was already answered, this method use previous work and answer prefix+l
	 * @param seeker
	 * @param query
	 * @param k
	 * @return
	 * @throws SQLException
	 */
	public int executeQueryPlusLetter(String seeker, List<String> query, int k, int t) throws SQLException{
		//if (query.size() != 1)
		//	System.out.println("Query+l: "+query.toString());
		String newPrefix = query.get(query.size()-1);
		String previousPrefix = newPrefix.substring(0, newPrefix.length()-1);
		this.updateKeys(previousPrefix, newPrefix);
		RadixTreeNode radixTreeNode = completionTrie.searchPrefix(newPrefix, false);
		if (radixTreeNode == null)
			return 0;
		String bestCompletion = radixTreeNode.getBestDescendant().getWord();
		List<DocumentNumTag> arr = this.invertedLists.get(bestCompletion);
		if (positions.get(bestCompletion) < arr.size()) {
			topValueQuery.put(newPrefix, arr.get(positions.get(bestCompletion)).getNum());
			topItemQuery.put(newPrefix, arr.get(positions.get(bestCompletion)).getDocId());
		}
		else {
			topValueQuery.put(newPrefix, 0);
			topItemQuery.put(newPrefix, -1l);
		}
		topValueQuery.remove(previousPrefix);
		topItemQuery.remove(previousPrefix);
		userWeights.put(newPrefix, userWeights.get(previousPrefix));
		userWeights.remove(previousPrefix);

		candidates.filterTopk(query);
		mainLoop(k, seeker, query, t);
		return 0;
	}

	/**
	 * MAIN LOOP of the TOPKS algorithm
	 * @param k : number of answers in the top-k
	 * @param seeker : 
	 * @param query
	 * @param t
	 * @throws SQLException
	 */
	protected void mainLoop(int k, String seeker, List<String> query, int t) throws SQLException{
		
		int loops=0;
		int steps = 1;
		boolean underTimeLimit = true;
		needUnseen = true;
		guaranteed = new HashSet<String>();
		possible = new HashSet<String>();
		long before_main_loop = System.currentTimeMillis();
		finished = false;
		int currVisited = 0;
		
		do{
			docs_inserted = false;
			boolean social = false;
			boolean socialBranch = chooseBranch(query);
			if(socialBranch){
				currVisited += 1;
				processSocial(query);
				social = true;
				if( (approxMethod&Methods.MET_TOPKS) == Methods.MET_TOPKS ) {
					lookIntoList(query);   //the "peek at list" procedure
				}
			}
			else {
				processTextual(query);
			}
			if(social) this.total_lists_social++;

			steps = (steps+1) % skippedTests;
			if( (steps == 0) || (!needUnseen && ( (approxMethod&Methods.MET_ET) == Methods.MET_ET) ) ) {
				try {
					/*
					 * During the terminationCondition method, look up at top_items of different ILs, we add
					 * them if necessary to the top-k answer of the algorithm.
					 */
					terminationCondition = candidates.terminationCondition(query, userWeight, k, query.size(), alpha, Params.number_users, 
																		   tag_idf, topValueQuery, userWeights, positions, approxMethod, 
																		   docs_inserted, needUnseen, guaranteed, possible);
				} catch (IOException e) {
					e.printStackTrace();
				}
				//For statistics only
				if(candidates.topkChange()){
					this.total_topk_changes++;
				}
				candidates.resetChange();
				long time_1 = System.currentTimeMillis();
				if ( (time_1 - before_main_loop) > t) {
					this.candidates.extractProbableTopK(k, guaranteed, possible, topValueQuery, userWeights, positions, approxMethod);
					underTimeLimit = false;
				}
			}
			else{
				terminationCondition=false;
			}
			long time_1 = System.currentTimeMillis();
			if ( (time_1-before_main_loop) > Math.max(t+25, t)) {
				underTimeLimit = false;
			}
			if (userWeight==0)
				terminationCondition = true;
			loops++;
			if (currVisited >= (this.nVisited+1))
				terminationCondition = true;
			
		} while(!terminationCondition && !finished && underTimeLimit);
		
		this.numloops=loops;
		System.out.println("There were "+loops+" loops ...");
	}

	/**
	 * When alpha > 0, we need to alternate between Social Branch and Textual Branch
	 * This method selects which branch will be chosen in the current loop
	 * @param query
	 * @return true (social) , false (textual)
	 */
	protected boolean chooseBranch(List<String> query) {
		
		double upper_social_score;
		double upper_docs_score;
		boolean textual = false;
		
		for(String tag: query) {
			
			if( (approxMethod&Methods.MET_TOPKS) == Methods.MET_TOPKS) {
				upper_social_score = (1-alpha) * userWeights.get(tag) * candidates.getSocialContrib(tag); // OK but strange if skipped_tests != 0
			}
			else
				upper_social_score = (1-alpha) * userWeights.get(tag) * tagFreqs.get(tag);
			
			if( (approxMethod&Methods.MET_TOPKS) == Methods.MET_TOPKS)
				upper_docs_score = alpha * candidates.getNormalContrib(tag);
			else
				upper_docs_score = alpha * topValueQuery.get(tag);
			
			if( !( (upper_social_score == 0) && (upper_docs_score == 0) ) ) finished = false;
			
			if((upper_social_score!=0) || (upper_docs_score!=0)) textual = textual || (upper_social_score <= upper_docs_score);
		
		}
		
        //return !textual;
        return random.nextBoolean();
	}


	/**
	 * Social process of the TOPKS algorithm
	 */
	protected void processSocial(List<String> query) throws SQLException{
		
		int currentUserId;
		int index = 0;
		String tag;
		int nbNeighbourTag = 0;
		
		// for all tags in the query Q, triples Tagged(u,i,t_j)
		for(int i=0; i<query.size(); i++) {
			tag = query.get(i);
			nbNeighbourTag = this.queryNbNeighbour.get(i);
			if (nbNeighbourTag > this.nbNeighbour){
				continue; // We don't need to analyse this word because it was already done previously
			}
			this.queryNbNeighbour.set(i, this.nbNeighbour+1);
			if(currentUser!=null){
				boolean found_docs = false;

				if((approxMethod&Methods.MET_APPR_MVAR)==Methods.MET_APPR_MVAR)
					d_distr.setPos(tag, userWeight, pos[index]+1);
				else if((approxMethod&Methods.MET_APPR_HIST)==Methods.MET_APPR_HIST)
					d_hist.setVals(tag, pos[index]+1, userWeight);
				if((this.approxMethod&Methods.MET_APPR_LAND)==Methods.MET_APPR_LAND){
					userWeights.put(tag, landmark.getMaxRemaining());
				}
				else{
					userWeights.put(tag, userWeight);
				}

				currentUserId = currentUser.getEntryId();
				long itemId = 0;
				
				if(this.userSpaces.containsKey(currentUserId) && !(currentUserId==seeker)){
					// HERE WE CHECK
					SortedMap<String, TLongSet> completions = userSpaces.get(currentUserId).prefixMap(tag);
					if (completions.size()>0) {
						Iterator<Entry<String, TLongSet>> iterator = completions.entrySet().iterator();
						while(iterator.hasNext()){
							Entry<String, TLongSet> currentEntry = iterator.next();
							String completion = currentEntry.getKey();
							
							for(TLongIterator it = currentEntry.getValue().iterator(); it.hasNext(); ){
								itemId = it.next();
								found_docs = true;
								Item<String> item = candidates.findItem(itemId, completion);
								if (item==null) {
									Item<String> item2 = candidates.findItem(itemId, "");
									
									if (item2!=null) {
										item = this.createCopyCandidateItem(item2, itemId, query, item, completion);
									}
									else {
										item = this.createNewCandidateItem(itemId, query,item, completion);
									}
								}
								else {
									
									candidates.removeItem(item);
								}
								float userW = 0;
								
								userW = userWeight;
								item.updateScore(tag, userW, pos[index], approxMethod);
								candidates.addItem(item);								
								docs_inserted = true;
								total_documents_social++;                            
							}
						}
					}
				}
				if(found_docs){
					total_conforming_lists++;
					docs_inserted = true;
				}
			}
			else{
				currentUserId = Integer.MAX_VALUE;
				pos[index]++;
				userWeight = 0;
				//float prev_part_sum = pos[index];
				if((approxMethod&Methods.MET_APPR_MVAR)==Methods.MET_APPR_MVAR)
					d_distr.setPos(tag, userWeight, pos[index]+1);
				else if((approxMethod&Methods.MET_APPR_HIST)==Methods.MET_APPR_HIST)
					d_hist.setVals(tag, pos[index]+1, userWeight);
				userWeights.put(tag, userWeight);
			}
			index++;
		}/* END FOR ALL TAGS IN QUERY Q */
		this.nbNeighbour++;
		if((this.approxMethod&Methods.MET_APPR_LAND)==Methods.MET_APPR_LAND){
			currentUser = landmark.getNextUser();
		}
		else{
			long time_loading_before = System.currentTimeMillis();
			currentUser = optpath.advanceFriendsList(currentUser);
			long time_loading_after = System.currentTimeMillis();
			long tl = (time_loading_after-time_loading_before)/1000;
			if (tl>1)
				System.out.println("Loading in : "+tl);
		}
		if(currentUser!=null)
			userWeight = currentUser.getDist().floatValue();
		else
			userWeight = 0.0f;
	}

	/**
	 * We advance on Inverted Lists here (method for social branch)
	 * Given the new discovered items in User Spaces, do top-items can be updated?
	 * @param query List<String>
	 */
	private void lookIntoList(List<String> query){
		int index=0;
		boolean found = true;
		while (found) {
			String completion;
			String autre = completionTrie.searchPrefix(query.get(query.size()-1), false).getBestDescendant().getWord(); // gros doute
			for(index=0;index<query.size();index++) {
				found = false;
				if (index == (query.size()-1)) //  prefix
					completion = completionTrie.searchPrefix(query.get(index), false).getBestDescendant().getWord();
				else {
					completion = query.get(index);
				}
				if(unknown_tf.get(query.get(index)).contains(topItemQuery.get(query.get(index))+"#"+completion)){
					Item<String> item1 = candidates.findItem(topItemQuery.get(query.get(index)), autre);
					if (item1==null) {
						unknown_tf.get(query.get(index)).remove(topItemQuery.get(query.get(index))+"#"+completion); // DON'T UNDERSTAND
						continue;
					}
					candidates.removeItem(item1);
					item1.updateScoreDocs(query.get(index), topValueQuery.get(query.get(index)), approxMethod);
					unknown_tf.get(query.get(index)).remove(topItemQuery.get(query.get(index))+"#"+completion); 
					if (index == (query.size()-1)) //  prefix
						advanceTextualList(query.get(index),index,false);
					else {
						advanceTextualList(query.get(index),index,true);
					}
					candidates.addItem(item1);
					found = true;
				}
			}
		}
	}

	/**
	 * We chose the textual branch (alpha > 0), we advance in lists
	 * @param query
	 * @throws SQLException
	 */
	protected void processTextual(List<String> query) throws SQLException{
		int index = 0;
		RadixTreeNode currNode = null;
		String currCompletion;
		for(String tag: query){
			if (this.queryNbNeighbour.get(index) > this.nbNeighbour) {
				index++;
				continue;
			}
			if(!topItemQuery.get(tag).equals(-1l)){
				currNode = completionTrie.searchPrefix(tag, false);
				currCompletion = currNode.getBestDescendant().getWord();
				Item<String> item = candidates.findItem(topItemQuery.get(tag), currCompletion);
				if(item == null) {
					Item<String> item2 = candidates.findItem(topItemQuery.get(tag), "");
					if (item2 != null) {
						item = this.createCopyCandidateItem(item2, topItemQuery.get(tag), query, item, currCompletion);
					}
					else {
						item = this.createNewCandidateItem(topItemQuery.get(tag), query,item, currCompletion);
					}
				}
				else
					candidates.removeItem(item);
				item.updateScoreDocs(tag, topValueQuery.get(tag), approxMethod);
				if(unknown_tf.get(tag).contains(item.getItemId()+"#"+currCompletion)) unknown_tf.get(tag).remove(item.getItemId()+"#"+currCompletion);
				candidates.addItem(item);
				docs_inserted = true;
				if ((index+1)==query.size()) // prefix, we don't search for exact match
					advanceTextualList(tag,index,false);
				else {
					advanceTextualList(tag,index,true);
				}
			}
			index++;
		}
	}

	/**
	 * Method to advance in inverted lists (using the trie)
	 * Used both in Social and Textual branches
	 * @param tag
	 * @param index
	 */
	protected void advanceTextualList(String tag, int index, boolean exact){
		RadixTreeNode current_best_leaf = completionTrie.searchPrefix(tag, exact).getBestDescendant();
		String word = current_best_leaf.getWord();
		List<DocumentNumTag> invertedList = this.invertedLists.get(word);
		positions.put(word, positions.get(word)+1);
		int position = positions.get(word);
		

		if(position < invertedList.size()){
			total_documents_asocial++;
			topValueQuery.put(tag, invertedList.get(position).getNum());
			topItemQuery.put(tag, invertedList.get(position).getDocId());
			current_best_leaf.updatePreviousBestValue(invertedList.get(position).getNum());
		}
		else{
			topValueQuery.put(tag, 0);
			topItemQuery.put(tag, -1l);
		}
	}

	/**
	 * Creates a new candidate in the discovered items of the query.
	 * @param itemId
	 * @param tagList (tags of query)
	 * @param item
	 * @param completion
	 * @return
	 * @throws SQLException
	 */
	protected Item<String> createNewCandidateItem(long itemId, List<String> tagList, Item<String> item, String completion) throws SQLException{
		
		item = new Item<String>(itemId, this.alpha, this.score,  this.d_distr, this.d_hist, this.error, completion);        
		int sizeOfQuery = tagList.size();
		int index = 0;
		
		for(String tag: tagList){
			index++;
			if (index < sizeOfQuery) {
				double stuff = tag_idf.searchPrefix(tag, true).getValue();
				if (Double.isNaN(stuff)) {
					System.out.println("BREAK POINT Double.isNaN: "+tag_idf.searchPrefix(tag, true)==null);
					System.exit(0);
				}
				item.addTag(tag, tag_idf.searchPrefix(tag, true).getValue());
			}
			else {
				item.addTag(tag, tag_idf.searchPrefix(completion, true).getValue());
			}
			unknown_tf.get(tag).add(itemId+"#"+completion);
		}
		return item;
	}
	
	/**
	 * Creates a new candidate copy of the given one, which will be used for new prefix
	 * @param itemId
	 * @param tagList
	 * @param item
	 * @param completion
	 * @return
	 * @throws SQLException
	 */
	protected Item<String> createCopyCandidateItem(Item<String> itemToCopy, long itemId, List<String> tagList, Item<String> copy, String completion) throws SQLException{
		copy = new Item<String>(itemId, this.alpha, this.score,  this.d_distr, this.d_hist, this.error, completion);        
		int sizeOfQuery = tagList.size();
		int index = 0;
		for(String tag:tagList){
			index++;
			if (index < sizeOfQuery) {
				copy.addTag(tag, tag_idf.searchPrefix(tag, true).getValue());
			}
			else {
				copy.addTag(tag, tag_idf.searchPrefix(completion, true).getValue());
			}
			unknown_tf.get(tag).add(itemId+"#"+completion);
		}
		copy.copyValuesFirstWords(tagList, itemToCopy);
		return copy;
	}

	/**
	 * Not used in current version
	 * @return
	 */
	public String statistics(){
		String tkpos="";
		return String.format(Locale.US, ""+
				"<br><stat><b>Time</b>: main loop <b>%.3f</b> sec</stat><br><br>"+
				"<stat><b>%d</b> total <b>user lists</b>, last proximity <b>%.3f</b></stat><br><br>"+
				"<stat><b>%d top-k changes</b>, last at position <b>%s</b></stat><br><br>"+
				"<stat><b>%d</b> docs in <b>user lists</b>, <b>%d</b> in <b>inverted lists</b>, <b>%d</b> random</stat><br><br>", 
				(float)time_loop/(float)1000,
				total_lists_social, this.userWeight,
				total_topk_changes, tkpos,
				total_documents_social, total_documents_asocial, total_rnd);
	}

	/**
	 * Gives the ranking of a given item in the ranked list of discovered items
	 * @param item
	 * @param k
	 * @return ranking (int)
	 */
	public int getRankingItem(long item, int k) {
		return this.candidates.getRankingItem(item, k);
	}

	/**
	 * Method to update unknown_tf HashMap from query to query+l
	 * @param previousPrefix
	 * @param newPrefix
	 */
	private void updateKeys(String previousPrefix, String newPrefix) {
		if (unknown_tf.containsKey(previousPrefix)) {
			HashSet<String> old_unknown_tf = unknown_tf.get(previousPrefix);
			HashSet<String> new_unknown_tf = new HashSet<String>();
			for (String unknownDoc: old_unknown_tf) {
				if (unknownDoc.startsWith(newPrefix))
					new_unknown_tf.add(unknownDoc);
			}
			unknown_tf.put(newPrefix, new_unknown_tf);
		}
	}

	public void setLandmarkPaths(LandmarkPathsComputing landmark){
		this.landmark = landmark;
	}

	/**
	 * Not used in current version
	 * @param originalUnprotectedString
	 * @return
	 */
	private String protectSpecialCharacters(String originalUnprotectedString) {
		if (originalUnprotectedString == null) {
			return null;
		}
		boolean anyCharactersProtected = false;

		StringBuffer stringBuffer = new StringBuffer();
		for (int i = 0; i < originalUnprotectedString.length(); i++) {
			char ch = originalUnprotectedString.charAt(i);

			boolean controlCharacter = ch < 32;
			boolean unicodeButNotAscii = ch > 126;
			boolean characterWithSpecialMeaningInXML = ch == '<' || ch == '&' || ch == '>';

			if (characterWithSpecialMeaningInXML || unicodeButNotAscii || controlCharacter) {
				stringBuffer.append("&#" + (int) ch + ";");
				anyCharactersProtected = true;
			} else {
				stringBuffer.append(ch);
			}
		}
		if (anyCharactersProtected == false) {
			return originalUnprotectedString;
		}

		return stringBuffer.toString();
	}

	public TreeSet<Item<String>> getResults(){
		TreeSet<Item<String>> results = new TreeSet<Item<String>>();
		for(String itid:candidates.get_topk())
			results.add(candidates.findItem(Long.parseLong(itid), ""));
		return results;
	}

	public Set<String> getTopKSet(){
		return candidates.get_topk();
	}

	public ArrayList<Integer> getVisited(){
		skr = new HashSet<Integer>();
		for(int i=0;i<Params.seeker.length;i++) skr.add(Params.seeker[i]);
		ArrayList<Integer> vst_u = new ArrayList<Integer>();
		for(int curr:vst){
			if(skr.contains(curr)) vst_u.add(curr);
		}
		this.visitedNodes=vst_u;
		return vst_u;
	}

	public String getNewResultsXML(boolean exact){
		String result="";
		result=String.format(Locale.US, "<ResultSet seeker=\"%s\" nbloops=\"%s\" isExact=\"%d\">", seeker, this.numloops, exact?1:0);

		result+=this.newXMLResults;
		if(!exact)
			result+=this.newBucketResults;
		result+=this.newXMLStats;
		result+="</ResultSet>\n";

		return result;
	}

	public String getResultsXML(){
		return this.newXMLResults;
	}

	public BasicSearchResult getResultsList(){
		return this.resultList;
	}

	public ArrayList<Integer> getViResult(){
		return this.visitedNodes;
	}

	public char[] getResultsForR() {
		char[] chaine=null;
		BasicSearchResult sr=new BasicSearchResult();
		sr.getResult();

		return chaine;
	}
	
	private static long getUsedMemory() {
		return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
	}

	/**
	 * Method to load file with triples in memory
	 * @throws IOException
	 */
	private void fileLoadingInMemory() throws IOException {
		final long start = getUsedMemory();
		this.completionTrie = new RadixTreeImpl(); // 
		this.positions = new HashMap<String, Integer>(16, 0.85f); // positions for a given keyword in the graph (useful for multiple words)
		this.tagFreqs = new HashMap<String,Integer>(16, 0.85f); //DONE BUT NOT USED
		this.tag_idf = new RadixTreeImpl(); //DONE
		this.invertedLists = new HashMap<String, List<DocumentNumTag>>(16, 0.85f); //DONE
		this.userSpaces = new TIntObjectHashMap<PatriciaTrie<TLongSet>>(16, 0.85f);
		this.dictionaryTrie = new PatriciaTrie<String>(); // trie on the dictionary of words
		userWeight = 1.0f;

		BufferedReader br;
		String line;
		String[] data;
		System.out.println("Beginning of file loading...");

		// Tag Inverted lists processing
		
		br = new BufferedReader(new FileReader(Params.dir+Params.ILFile));
		List<DocumentNumTag> currIL;
		int counter = 0;
		while ((line = br.readLine()) != null) {
			data = line.split("\t");
			if (data.length < 2)
				continue;
			String tag = data[0];
			if (!this.invertedLists.containsKey(data[0]))
				this.invertedLists.put(tag, new ArrayList<DocumentNumTag>());
			currIL = this.invertedLists.get(data[0]);
			for (int i=1; i<data.length; i++) {
				String[] tuple = data[i].split(":");
				if (tuple.length != 2)
					continue;
				currIL.add(new DocumentNumTag(Long.parseLong(tuple[0]), Integer.parseInt(tuple[1])));
			}
			Collections.sort(currIL, Collections.reverseOrder());
			DocumentNumTag firstDoc = currIL.get(0);
			completionTrie.insert(tag, firstDoc.getNum());
			positions.put(tag, 0);
			tagFreqs.put(tag, firstDoc.getNum());
			counter++;
			if ((counter%50000)==0)
				System.out.println("\t"+counter+" tag ILs loaded");
		}
		br.close();
		final long size = ( getUsedMemory() - start) / 1024 / 1024;
		System.out.println("Inverted List file = " + size + "M");

		System.out.println("Inverted List file loaded...");

		// Triples processing
		int userId;
		long itemId;
		String tag;
		final long start2 = getUsedMemory();
		br = new BufferedReader(new FileReader(Params.dir+Params.triplesFile));
		counter = 0;
		System.out.println("Loading of triples");
		while ((line = br.readLine()) != null) {
			data = line.split("\t");

			if (data.length != 3)
				continue;
			userId = Integer.parseInt(data[0]);
			itemId = Long.parseLong(data[1]);
			tag = data[2];
			if (!dictionaryTrie.containsKey(tag))
				dictionaryTrie.put(tag, "");
			if(!this.userSpaces.containsKey(userId)){
				this.userSpaces.put(userId, new PatriciaTrie<TLongSet>());
			}
			if(!this.userSpaces.get(userId).containsKey(tag))
				this.userSpaces.get(userId).put(tag, new TLongHashSet());
			this.userSpaces.get(userId).get(tag).add(itemId);
			counter++;
			if ( (counter%1000000) == 0)
				System.out.println("\t"+counter+" triples loaded");
		}
		br.close();
		final long size2 = ( getUsedMemory() - start2) / 1024 / 1024;
		System.out.println("User spaces file = " + size2 + "M");
		
		Params.number_users = this.userSpaces.size();

		// Tag Freq processing
		final long start3 = getUsedMemory();
		br = new BufferedReader(new FileReader(Params.dir+Params.tagFreqFile));
		int tagfreq;
		while ((line = br.readLine()) != null) {
			data = line.split("\t");
			if (data.length != 2)
				continue;
			tag = data[0];
			tagfreq = Integer.parseInt(data[1]);
			//float tagidf = (float) Math.log(((float)Params.number_documents - (float)tagfreq + 0.5)/((float)tagfreq+0.5));
			float tagidf = (float) Math.log((float)Params.number_documents / ((float) tagfreq) ); // Old tf-idf
			tag_idf.insert(tag, tagidf);
		}
		br.close();
		final long size3 = ( getUsedMemory() - start3) / 1024 / 1024;
		System.out.println("TagFreq file = " + size3 + "M");
	}

	/**
	 * Method to load triples from a database to memory
	 * @throws SQLException
	 */
	private void dbLoadingInMemory() throws SQLException{
		this.connection = dbConnection.DBConnect();
		PreparedStatement ps;
		ResultSet rs = null;
		this.dictionaryTrie = new PatriciaTrie<String>();

		// DICTIONARY
		dictionary = new ArrayList<String>();
		try {
			String sqlRequest = String.format(sqlGetDifferentTags, Params.taggers);
			ps = connection.prepareStatement(sqlRequest);
			rs = ps.executeQuery();
			while(rs.next()) {
				dictionary.add(rs.getString(1));
				dictionaryTrie.put(rs.getString(1), "");
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		System.out.println("Dictionary loaded, "+dictionary.size()+"tags...");

		// INVERTED LISTS
		ResultSet result;
		userWeight = 1.0f;
		completionTrie = new RadixTreeImpl();
		positions = new HashMap<String, Integer>();
		userWeights = new HashMap<String,Float>();
		tagFreqs = new HashMap<String,Integer>();
		int tagFreqDoc = 0;
		tag_idf = new RadixTreeImpl();
		HashMap<String, ResultSet> docs3 = new HashMap<String, ResultSet>();
		String[] dictionary2 = { // DEBUG PURPOSE
				"Obama", //twitter dump
				"Cancer",
				"Syria",
				"SOUGOFOLLOW",
				"Apple",
				"NoMatter",
				"SOUGOF",
				"SOUGOFOL",
				"TFBj",
				"TFBJ", 
				"TFBUSA",
				"TFB",
				"TFB_VIP",
				"TFBPH",
				"TFB_TeamFollow",
				"TFBINA", 
				"TFBFI", 
				"TFBjp", 
				"TFBJp", 
				"TFBJP",
				"openingact",
				"openingceremony"
		};
		for(String tag:dictionary2){
			/*
			 * INVERTED LISTS ARE HERE
			 */
			ps = this.connection.prepareStatement(sqlGetDocsListByTag);
			ps.setString(1, tag);
			docs3.put(tag, ps.executeQuery()); // INVERTED LIST
			if(docs3.get(tag).next()){
				int getInt2 = docs3.get(tag).getInt(2);
				//String getString1 = docs3.get(tag).getString(1);
				tagFreqDoc = getInt2;
				completionTrie.insert(tag, getInt2);
			}
			positions.put(tag, 0);
			userWeights.put(tag, userWeight);
			ps = connection.prepareStatement(sqlGetTagFrequency);
			ps.setString(1, tag);
			result = ps.executeQuery();
			int tagfreq = 0;
			if(result.next()) tagfreq = result.getInt(1);
			tagFreqs.put(tag, tagFreqDoc);
			float tagidf = (float) Math.log(((float)Params.number_documents - (float)tagfreq + 0.5)/((float)tagfreq+0.5));
			tag_idf.insert(tag, tagidf);

		}
		System.out.println("Inverted Lists loaded...");

		// USER SPACES
		sqlGetAllDocuments = String.format(sqlGetAllDocumentsTemplate, this.tagTable);
		int idx=0;

		for(String tag:dictionary2) {
			if(idx<dictionary2.length-1){
				sqlGetAllDocuments+=String.format("\'%s\',", tag);
			}
			else{
				sqlGetAllDocuments+=String.format("\'%s\')", tag);
			}
			idx++;
		}

		this.userSpaces = new TIntObjectHashMap<PatriciaTrie<TLongSet>>();
		connection.setAutoCommit(false);
		Statement stmt = connection.createStatement();
		stmt.setFetchSize(1000);
		result = stmt.executeQuery(sqlGetAllDocuments); //DEBUG PURPOSE, SMALL DATA SET
		while(result.next()){
			int d_usr = result.getInt(1);
			long d_itm = Long.parseLong(result.getString(2));
			String d_tag = result.getString(3);
			if(!this.userSpaces.containsKey(d_usr)){
				this.userSpaces.put(d_usr, new PatriciaTrie<TLongSet>());
			}
			this.userSpaces.get(d_usr).put(d_tag, new TLongHashSet());
			this.userSpaces.get(d_usr).get(d_tag).add(d_itm);
		}
		System.out.println("Users spaces loaded");
	}

	public JsonObject getJsonAnswer(int k, String query, boolean exact) {
		JsonObject jsonResult = new JsonObject();
		JsonArray arrayResults = new JsonArray();
		JsonObject currItem;
		int n = 0;
		
		for (Item<String> item: this.candidates.getTopK(k, query, exact)) {
			n++;
			//item.debugging();
			currItem = new JsonObject();
			currItem.add("id", new JsonPrimitive(item.getItemId()));					// id of the item
			currItem.add("rank", new JsonPrimitive(n));									// position of item
			currItem.add("completion", new JsonPrimitive(item.getCompletion()));	   	// completion of the term (term if already complete word)
			currItem.add("textualScore", new JsonPrimitive( item.getTextualScore() )); 	// sum( idf(t) * tf(item | t ) for t in query)
			currItem.add("socialScore", new JsonPrimitive( item.getSocialScore() ));   	// sum( idf(t) * sf(item | s, t ) for t in query)
			arrayResults.add(currItem);
		}
		
		jsonResult.add("status", new JsonPrimitive(1));
		jsonResult.add("nLoops", new JsonPrimitive(this.numloops));
		jsonResult.add("n", new JsonPrimitive(n));
		jsonResult.add("results", arrayResults);

		return jsonResult;
	}

	public void setSkippedTests(int skippedTests) {
		this.skippedTests = skippedTests;
	}

}