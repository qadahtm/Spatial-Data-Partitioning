package experiments;

import helpers.Constants;
import helpers.SplitMergeInfo;
import index.RTree;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextInputFormat;

import com.turn.platform.cheetah.partitioning.horizontal.CostEstimator;
import com.turn.platform.cheetah.partitioning.horizontal.DynamicPartitioning;
import com.turn.platform.cheetah.partitioning.horizontal.Partition;
import com.turn.platform.cheetah.partitioning.horizontal.Solution;

import experiments.DynamicRQDriver.Map;

import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.TaskReport;

import partitioning.PartitioningDriver;

public class RQDriver{

	private static ArrayList<Partition> getRandomQLoad(int numQueries) {
		ArrayList<Partition> qLoad = new ArrayList<Partition>();
		for (int i = 0; i < numQueries; i++) {
			int left = (int)(Math.random() * 100);
			int right = (int)(Math.random() * 100) + left;
			int bottom = (int)(Math.random() * 100);
			int top = (int)(Math.random() * 100) + bottom;

			//qLoad.add(new Partition(bottom, top, left, right));

			left = 800 + (int)(Math.random() * 10);
			right = (int)(Math.random() * 10) + left;
			bottom = 800 + (int)(Math.random() * 10);
			top = (int)(Math.random() * 10) + bottom;

			//qLoad.add(new Partition(bottom, top, left, right));

			left = 300 + (int)(Math.random() * 10);
			//right = (int)(Math.random() * 10) + left;
			right = 1 + left;
			bottom = 500 + (int)(Math.random() * 10);
			//top = (int)(Math.random() * 10) + bottom;
			top = 1 + bottom;

			qLoad.add(new Partition(bottom, top, left, right));

			left = 0;
			right  = Constants.gridWidth;
			bottom = 0;
			top = Constants.gridHeight;

			//qLoad.add(new Partition(bottom, top, left, right));
		}
		return qLoad;
	}

	// args[0] = a string: static, dynamic, grid
	// args[1] = k
	// args[2] = the number of queries you want to run. (x-axis of figure)
	// args[3] = input directory
	// args[4] = output directory
	// args[5] = statistics file name (experiment's results)
	public static void main(String[] args) throws IOException {
		String experimentType = args[0];
		if (!experimentType.equalsIgnoreCase("static") && 
			!experimentType.equalsIgnoreCase("dynamic") &&
			!experimentType.equalsIgnoreCase("grid")) {
			System.out.println("Please enter a valid experiment type as an argument. You may enter any of " +
					"static, dynamic, grid as the first argument");
			return;
		}
		int k = Integer.parseInt(args[1]);
		int numQueries = Integer.parseInt(args[2]);
		String inputDir = args[3];
		if (inputDir.charAt(inputDir.length() - 1) != '/') {
			inputDir += "/";
		}
		String outputDir = args[4];
		String statsFileName = args[5];

		System.out.println("Experiment is for " + experimentType + " partitions");
		System.out.println("k = " + k);
		System.out.println("Number of  Queries to run = " + numQueries);
		System.out.println("Input Directory = " + inputDir);
		System.out.println("Output Directory = " + outputDir);
		System.out.println("Stats Output File Name = " + statsFileName);

		BufferedWriter out = new BufferedWriter(new FileWriter(statsFileName));
		out.write("Query ID, Total Execution Time, CPU Time of Mappers, AVG CPU Time of Mappers, HDFS Bytes Read\r\n");

		RTree<Partition> gridPartitionsRTree = new RTree<Partition>(10, 2, 2); // used only for grid.
		DynamicPartitioning partitioning = null; // used for dynamic and static partitioning.

		// static and dynamic
		if (experimentType.equalsIgnoreCase("static") || 
			experimentType.equalsIgnoreCase("dynamic")) {
			CostEstimator costEstimator = new CostEstimator(null, null, Constants.gridWidth, Constants.gridHeight);
			partitioning = new DynamicPartitioning(costEstimator, k);			
			partitioning.initialPartitions();
		} else {  // grid
			for (Partition p : PartitioningDriver.getGridPartitions(k))
				gridPartitionsRTree.insert(p.getCoords(), p.getDimensions(), p);
		}

		int i = 0;
		for (Partition query : getRandomQLoad(numQueries)) {
			List<Partition> overlappingPartitions = new ArrayList<Partition>();
			if (!experimentType.equalsIgnoreCase("grid")) {
				//overlappingPartitions = partitioning.partitionsRTree.searchExclusive(query.getCoords(), query.getDimensions());
				for (Partition p : partitioning.partitionsRTree.searchExclusive(query.getCoords(), query.getDimensions())) {
					overlappingPartitions.add(p);	
				}					
			} else {
				overlappingPartitions = gridPartitionsRTree.searchExclusive(query.getCoords(), query.getDimensions());
			}
			// Execute and report stats.
			exec(query, ++i, inputDir, outputDir, overlappingPartitions, out);

			// In case of dynamic, we may need to split and merge.
			if (experimentType.equalsIgnoreCase("dynamic")) {
				SplitMergeInfo splitMergeInfo = partitioning.processNewQuery(query);
				if (splitMergeInfo != null) {
					System.out.println("Splitting and Merging");					
					mergeAndSplitPartitions(inputDir, splitMergeInfo);
				}
			}
		}
		out.close();
	}

	private static void exec(Partition query, int i, String inputDir, String outputDir,
			List<Partition> overlappingPartitions, BufferedWriter out)  throws IOException {
		Configuration mycon=new Configuration();
		JobConf conf = new JobConf(mycon, RQDriver.class);
		FileSystem fs = FileSystem.get(conf);                
		fs.delete(new Path(outputDir), true);
		//conf.setNumReduceTasks(0);
		conf.set("mapred.min.split.size", Long.toString(fs.getDefaultBlockSize()));				// More Robust
		conf.set("mapred.sort.avoidance", "true");
		conf.set("mapreduce.tasktracker.outofband.heartbeat", "true");
		conf.set("mapred.compress.map.output", "false");
		conf.set("mapred.job.reuse.jvm.num.tasks", "-1");				// Infinity

		conf.setOutputKeyClass(Text.class);
		conf.setMapOutputKeyClass(Text.class);
		conf.setOutputValueClass(IntWritable.class);

		conf.setMapperClass(Map.class);
		//conf.setReducerClass(Reduce.class);

		conf.setInputFormat(TextInputFormat.class);

		long qLeft = Constants.minLong + query.getLeft() * (Constants.maxLong - Constants.minLong) / 1000;			
		long qRight = Constants.minLong + query.getRight() * (Constants.maxLong - Constants.minLong) / 1000;
		long qBottom = Constants.minLat + query.getBottom() * (Constants.maxLat - Constants.minLat) / 1000;
		long qTop = Constants.minLat + query.getTop() * (Constants.maxLat - Constants.minLat) / 1000;

		conf.set("minLat", Long.toString(qBottom));
		conf.set("minLong", Long.toString(qLeft));
		conf.set("maxLat", Long.toString(qTop));
		conf.set("maxLong", Long.toString(qRight));

		System.out.println("Number of overlapping partitions: " + overlappingPartitions.size());

		for (Partition overlapping : overlappingPartitions) {
			Path overlappingPartitionPath = new Path(inputDir + overlapping.getBottom() + "," + overlapping.getTop() + "," + overlapping.getLeft() + "," + overlapping.getRight());
			if (fs.exists(overlappingPartitionPath)) {
				FileInputFormat.addInputPath(conf, overlappingPartitionPath);
			} else {
				System.out.println("path = " + overlappingPartitionPath);
				System.out.println("WARNING: Path of a partition was not found in the HDFS. This should never happen. Please debug.");
			}
		}
		if (FileInputFormat.getInputPaths(conf).length > 0) {
			FileOutputFormat.setOutputPath(conf,new Path(outputDir));

			long start = System.nanoTime();
			RunningJob runjob = JobClient.runJob(conf);
			long end = System.nanoTime();
			double elapsedTime = (end - start)/1000000000.0;

			out.write(i + ",");
			System.out.println("Elapsed Time = " + elapsedTime);
			out.write(elapsedTime + ",");

			JobClient jobclient = new JobClient(conf);
			TaskReport [] maps = jobclient.getMapTaskReports(runjob.getID());
			long mapDuration = 0;
			for(TaskReport rpt: maps){
				mapDuration += rpt.getFinishTime() - rpt.getStartTime();					    					    					    
			}

			System.out.println("Time Spent By Mappers " + mapDuration);
			System.out.println("Avg Time Spent Per Mapper " + mapDuration/maps.length);
			out.write(mapDuration + ",");
			out.write(mapDuration/maps.length + ",");
			long hdfsBytesRead = runjob.getCounters().findCounter("FileSystemCounters", "HDFS_BYTES_READ").getValue();
			out.write(hdfsBytesRead + "\r\n");
			System.out.println("HDFS BYTES READ " + hdfsBytesRead);	
			out.flush();
		} else {
			System.out.println("WARNING: No partitions were added for the job. This should never happen. Please debug.");
		}
	}

	static class Map extends MapReduceBase implements Mapper<LongWritable, Text, Text, IntWritable> {

		double minLat;
		double minLong;
		double maxLat;
		double maxLong;

		public void configure(JobConf job) {
			minLat = Integer.parseInt(job.get("minLat"));
			minLong = Integer.parseInt(job.get("minLong"));
			maxLat = Integer.parseInt(job.get("maxLat"));
			maxLong = Integer.parseInt(job.get("maxLong"));
		}

		public void map(LongWritable key, Text value,OutputCollector<Text, IntWritable> output, Reporter reporter) throws IOException {
			String [] tokens=value.toString().split(",");

			int latitude = (Integer.parseInt(tokens[0]));
			int longitude = (Integer.parseInt(tokens[1]));

			if (latitude >= minLat && latitude <= maxLat && longitude >= minLong && longitude <= maxLong) {
				Text word = new Text();
				word.set("one");
				output.collect(word, new IntWritable(1));
			}

		}
	}

	static class Reduce extends MapReduceBase implements Reducer<Text, IntWritable, Text, LongWritable> {
		public void reduce(Text key, Iterator<IntWritable> values,OutputCollector<Text, LongWritable> output, Reporter reporter) throws IOException {

			int sum = 0;

			while (values.hasNext()) {
				sum+=values.next().get();				
			}
			System.out.println(sum);
			output.collect(key, new LongWritable(sum));
		}
	}

	private static void mergeAndSplitPartitions(String inputDir, SplitMergeInfo splitMergeInfo) {

		Configuration mycon=new Configuration();
		JobConf conf = new JobConf(mycon, RQDriver.class);
		FileSystem fs = null;
		try {
			fs = FileSystem.get(conf);
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		// Merging
		Path mergeParentPath = new Path(inputDir + splitMergeInfo.mergeParent.getBottom() + "," + splitMergeInfo.mergeParent.getTop() + "," + splitMergeInfo.mergeParent.getLeft() + "," + splitMergeInfo.mergeParent.getRight());
		Path mergeChild0Path = new Path(inputDir + splitMergeInfo.mergeChild0.getBottom() + "," + splitMergeInfo.mergeChild0.getTop() + "," + splitMergeInfo.mergeChild0.getLeft() + "," + splitMergeInfo.mergeChild0.getRight());
		Path mergeChild1Path = new Path(inputDir + splitMergeInfo.mergeChild1.getBottom() + "," + splitMergeInfo.mergeChild1.getTop() + "," + splitMergeInfo.mergeChild1.getLeft() + "," + splitMergeInfo.mergeChild1.getRight());

		System.out.println("Merging " + mergeChild0Path + " and " + mergeChild1Path);

		try {
			if (!fs.exists(mergeChild0Path) || !fs.exists(mergeChild1Path))
				return;

			BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fs.create(mergeParentPath)));

			BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(mergeChild0Path)));
			String line;
			line = br.readLine(); 
			while (line != null){
				bw.write(line + "\r\n");
				line=br.readLine();
			}
			br.close();

			br = new BufferedReader(new InputStreamReader(fs.open(mergeChild1Path)));
			line = br.readLine(); 
			while (line != null){
				bw.write(line + "\r\n");
				line=br.readLine();
			}
			br.close();

			bw.close();
			fs.delete(mergeChild0Path);
			fs.delete(mergeChild1Path);
			System.out.println("Done Meging into " + mergeParentPath);

			// Splitting
			Path splitParentPath = new Path(inputDir + splitMergeInfo.splitParent.getBottom() + "," + splitMergeInfo.splitParent.getTop() + "," + splitMergeInfo.splitParent.getLeft() + "," + splitMergeInfo.splitParent.getRight());
			Path splitChild0Path = new Path(inputDir + splitMergeInfo.splitChild0.getBottom() + "," + splitMergeInfo.splitChild0.getTop() + "," + splitMergeInfo.splitChild0.getLeft() + "," + splitMergeInfo.splitChild0.getRight());
			Path splitChild1Path = new Path(inputDir + splitMergeInfo.splitChild1.getBottom() + "," + splitMergeInfo.splitChild1.getTop() + "," + splitMergeInfo.splitChild1.getLeft() + "," + splitMergeInfo.splitChild1.getRight());

			System.out.println("Splitting " + splitParentPath);

			long left0 = Constants.minLong + splitMergeInfo.splitChild0.getLeft() * (Constants.maxLong - Constants.minLong) / 1000;			
			long right0 = Constants.minLong + splitMergeInfo.splitChild0.getRight() * (Constants.maxLong - Constants.minLong) / 1000;
			long bottom0 = Constants.minLat + splitMergeInfo.splitChild0.getBottom() * (Constants.maxLat - Constants.minLat) / 1000;
			long top0 = Constants.minLat + splitMergeInfo.splitChild0.getTop() * (Constants.maxLat - Constants.minLat) / 1000;

			BufferedWriter bw0 = new BufferedWriter(new OutputStreamWriter(fs.create(splitChild0Path)));
			BufferedWriter bw1 = new BufferedWriter(new OutputStreamWriter(fs.create(splitChild1Path)));
			br = new BufferedReader(new InputStreamReader(fs.open(splitParentPath)));
			line = br.readLine(); 
			while (line != null){
				String [] tokens = line.split(",");

				int latitude = (Integer.parseInt(tokens[0]));
				int longitude = (Integer.parseInt(tokens[1]));

				if (latitude >= bottom0 && latitude < top0 && longitude >= left0 && longitude <= right0) {
					bw0.write(line + "\r\n");
				} else {
					bw1.write(line + "\r\n");
				}

				line=br.readLine();
			}


			br.close();
			bw0.close();
			bw1.close();


			fs.delete(splitParentPath);

			System.out.println("Done Splitting int " + splitChild0Path + " and " + splitChild1Path);

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
