import java.util.*;

/* version of hash table simulation that stores both flow id and count
 in the hash table, but uses the count-min sketch to estimate the loss 
 for a flow that is not yet in the hash table and just came in; this is
 used to perform the eviction heuristic */

 /* NOTE: Accuracy isn't better than just maintaining the flow id in
 the hash table - simulations are in Smart Eviction Simulation file*/

public class EvictingMinHashTableSimulation{
	public static void main(String[] args){
		int numberOfTrials = Integer.parseInt(args[0]);
		int numberOfFlows = Integer.parseInt(args[1]);
		int tableSize = Integer.parseInt(args[2]);
		double threshold = 0.0025;
		FlowWithCount[] buckets = new FlowWithCount[tableSize];
		int D = 2;
		int droppedPacketInfoCount = 0;
		int cumDroppedPacketInfoCount = 0;
		int totalNumberOfPackets = 0;

		// hardcoded values for the hash functions given that the number of flows is 100
		final int P = 1019;
		final int hashA[] = {421, 149};
		final int hashB[] = {73, 109};

		/*Sketch that maintains the loss of each flow*/
		Sketch countMinSketch = new Sketch(100, 3, numberOfFlows);

		List<Integer> packets = new ArrayList<>();
		// add i packets of  flowid i
		for (int i = 1; i <= numberOfFlows; i++)
			for (int j = 0; j < i; j++)
				packets.add(i);

		// ideally the big losers should be the highest flow ids until the loss falls below the threshold
		HashSet<Integer> expectedLossyFlows = new HashSet<Integer>();
		for (int i = numberOfFlows; i >= 0; i--){
			// we know that there are i lost packets of flow i
			if (i > (int) Math.floor(threshold*packets.size())) {
				expectedLossyFlows.add(i);
			}
		}

		// array that counts the number of ith packets lost across the trials
		int packetsInfoDroppedAtFlow[] = new int[numberOfFlows];
		double observedProbPacketsDroppedAtFlow[] = new double[numberOfFlows];
		double expectedProbPacketsDroppedAtFlow[] = new double[numberOfFlows];

		// initialize all the flow tracking buckets to flows with id 0 and count 0
		buckets = new FlowWithCount[tableSize];
		for (int j = 0; j < tableSize; j++){
			buckets[j] = new FlowWithCount(0, 0);
		}

		// Across many trials, find the total number of packets lost, track the flows they belong to in a dleft hash table
		// at the end of the hashing procedure, look through all the tracked flows and see which of them are the big losers
		// compare this against the expected big losers and see if there is a discrepancy between the answer as to what the
		// big losers are, if yes, find how much of the loss went unreported as a fraction of the total loss
		double cumErrorMargin = 0;
		int errorInBinaryAnswer = 0;
		for (int i = 0; i < numberOfTrials; i++){
			Collections.shuffle(packets);
			countMinSketch.reset();						
			FlowWithCount.reset(buckets);

			droppedPacketInfoCount = 0;
			totalNumberOfPackets = 0; // needed for the denominator to compute the threshold for loss count

			// data plane operation - as lost packets flow, hash them using d-left hashing to track the lossy flows
			// each packets comes in as a lost packet, put it in the count min sketch and also the hash table
			for (int j = 0; j < packets.size(); j++){
				totalNumberOfPackets++;

				// update the count-min sketch for this flowid
				countMinSketch.updateCount(packets.get(j));

				/* uniform hashing into a chunk N/d and then dependent picking of the choice*/
				int index = 0;
				int k = 0;

				// keep track of which of the d locations has the minimum lost packet count
				// use this location to place the incoming flow if there is a collision
				int minIndex = 0;
				long minValue = -1;

				for (k = 0; k < D; k++){
					index = ((hashA[k]*packets.get(j) + hashB[k]) % P) % (tableSize/D) + (k*tableSize/D);
					//int index = (int) ((packets.get(j)%(tableSize/D)) *(tableSize/D) + k*tableSize/D);
					// this flow has been seen before
					if (buckets[index].flowid == packets.get(j)) {
						buckets[index].count++;
						break;
					}

					// new flow
					if (buckets[index].flowid == 0) {
						buckets[index].flowid = packets.get(j);
						buckets[index].count = 1;
						break;
					}

					// track min - first time explicitly set the value
					if (buckets[index].count < minValue || k == 0){
						minValue = buckets[index].count;
						minIndex = index;
					}
				}

				// none of the D locations were free - hash collission
				// decide if one of them is worth evicting

				// figure out if the incoming flow has a higher loss than the flow that has the minimum loss amongst
				// the d existing flows in the table
				if (k == D) {
					if (countMinSketch.estimateLossCount(buckets[minIndex].flowid) < countMinSketch.estimateLossCount(packets.get(j))){
						packetsInfoDroppedAtFlow[packets.get(j) - 1] = 0;
						packetsInfoDroppedAtFlow[buckets[minIndex].flowid - 1] = (int) buckets[minIndex].count;
						droppedPacketInfoCount = droppedPacketInfoCount + (int) buckets[minIndex].count - (int) countMinSketch.estimateLossCount(packets.get(j));
						buckets[minIndex].flowid = packets.get(j);
						buckets[minIndex].count = (int) countMinSketch.estimateLossCount(packets.get(j));
					}
					else{
						packetsInfoDroppedAtFlow[packets.get(j) - 1]++;
						droppedPacketInfoCount++;
					}
				}						
			}

			cumDroppedPacketInfoCount += droppedPacketInfoCount;

			// controller operation at regular intervals
			// go through all the entries in the hash table and check if any of them are above the total loss count
			HashSet<Integer> observedLossyFlows = new HashSet<Integer>();
			for (FlowWithCount f : buckets){
				if (f.count > threshold*totalNumberOfPackets)
					observedLossyFlows.add(f.flowid);
			}

			// compare observed and expected lossy flows and compute the probability of error
			int bigLoserPacketsLost = 0;
			int flag = 0;
			for (Integer flowid : expectedLossyFlows){
				if (!observedLossyFlows.contains(flowid)){
					if (flag != 1){
						// if there is even one point of difference, there is an error in binary yes/no or what flows contributed to loss
						errorInBinaryAnswer++; 
						flag = 1;
					}
					bigLoserPacketsLost += flowid; // as many packets as the flowid have been lost from the information gathered
				}
			}

			double errorMargin = bigLoserPacketsLost/(double) totalNumberOfPackets;
			cumErrorMargin += errorMargin;
		}

		// chances of an error in binary answer
		System.out.print(numberOfFlows + ", " + tableSize + ", " + threshold + ",");
		System.out.print(errorInBinaryAnswer/(double) numberOfTrials + "," + cumErrorMargin/ numberOfTrials + ",");
		System.out.println(cumDroppedPacketInfoCount/(double) numberOfTrials + ",");
	}
}