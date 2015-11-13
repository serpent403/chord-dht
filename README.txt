HOW TO RUN:
$ sbt run numNodes numRequests
Eg: $ sbt run 10 2

numNodes is the number of peers to be created in the Chord system.
numRequests is the number of requests each peer has to make.

WHAT IS WORKING:
The nodes are assigned an m-bit id, i.e. by hashing the node’s IP address (generated IP address with random generator) using SHA-1. Although the SHA-1 output is 160 bit, it is truncated to m bits (calculation of `m` is done in the code). The finger tables and <successor, predecessor> information of relevant nodes are updated accordingly every time a node is joined.
Each node makes `numRequests`, i.e, number of search requests in the Chord network. In each request an m-bit key is generated for which the node searches in the network.
When all nodes have performed `numRequests` number of search requests, the program exits displaying the average number of hops traversed per request.
Note: Each peer sends a request/second. This has been done with the help of “akka scheduler” library.

WHAT IS THE MAXIMUM NETWORK SIZE YOU MANAGED TO DEAL WITH:
The maximum network size we executed the program is 10000 nodes.
Note: Additional Information such as sample Finger Tables, graph for Number of Nodes vs Average Number of Hops and explanations can be found in the Analysis.pdf file.
