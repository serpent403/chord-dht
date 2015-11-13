import akka.actor._
import akka.pattern._
import akka.dispatch._
import java.security.MessageDigest
import util.control.Breaks._
import com.typesafe.config.ConfigFactory
import scala.collection._
import scala.collection.mutable.ArrayBuffer
import akka.actor.Props
import java.util.concurrent._
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import sun.reflect.ReflectionFactory.GetReflectionFactoryAction
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.language.postfixOps
import com.sun.org.apache.xalan.internal.xsltc.compiler.Pattern

case class FindSuccessor(id: Int, sourceNodeId: Int)
case class NodeSuccessor()
case class NodePredecessor()
case class SetSuccessor(pred: ActorRef)
case class SetPredecessor(pred: ActorRef)
case class ClosestPreceedingFinger(id: Int)
case class UpdateFingerTable(newNode: ActorRef, i: Int)
case class MoveKeys(newNode: Int, pred: Int)
case class PrintFingerTable()

object Nodes {
  var arr = ArrayBuffer[ActorRef]()
  var present = collection.mutable.Map[Int, ActorRef]()
  var successors = collection.mutable.Map[Int, ActorRef]()
  var predecessors = collection.mutable.Map[Int, ActorRef]()
  val r = scala.util.Random
  var sha = MessageDigest.getInstance("SHA-1")
  var requests:Int = _
  var reqs = collection.mutable.Map[Int, Int]()
  var hops = collection.mutable.Map[Int, Int]()
  var allJoined:Boolean = _
  var startSchedIn: Int = 0 // seconds

  def getRandom(avoidNode: ActorRef): ActorRef  = {
    var arr_buff = ArrayBuffer[ActorRef]()
    arr.copyToBuffer(arr_buff)
    arr_buff -= avoidNode
    
    if (arr_buff.length == 0) {
      return null
    } else {
      var index = r.nextInt(arr_buff.length)
      return arr_buff(index)
    }
  }
  
  def getKey(str: String): String = {
    var s = str
    
    var truncateChars = (Settings.m / 4).toInt // since 1 hex char is 4 bits
    var hexStr = sha.digest(s.getBytes).map{ b => String.format("%02X", new java.lang.Integer(b & 0xff)) }.mkString
    
    var id:String = null
    
    do {
      id = Integer.parseInt(hexStr.substring(0, truncateChars), 16).toString() 
      s  = Nodes.r.alphanumeric.take(10).mkString
      hexStr = sha.digest(s.getBytes).map{ b => String.format("%02X", new java.lang.Integer(b & 0xff)) }.mkString
      
    } while(Nodes.present.keySet.exists(_ == id.toInt)) 
    
    return id
  }

}

object Err {
  var flag:Boolean = _
  var act:ActorRef = _
  var task = new java.util.TimerTask {
    def run = {}
  }
}

object Settings {
  var numNodes:Int = _
  var numRequests:Int = _
  var m:Int = _
  var totalKeys:Int = _
  var lastKey: Int = _
}

object TimeOut {
  var t = new Timeout(Duration.create(5, "seconds"))
}

class CircularRange {
  // set range start and end
  var x:Int = _
  var s:Int = _
  var e:Int = _
  var sameEnds:Boolean = _
  
  def set(search: Int, start_point: Int, end_point: Int) = {
    x = search
    s = start_point
    e = end_point
      
    if(e < s) { // wrapping occurred in circle
      e = Settings.lastKey + 1 + e // eg: <n, k, n_succ> = <6, 8, 1>
        
      if(x < s) { // eg: <n, k, n_succ> = <6, 1, 2>
        x = Settings.lastKey + 1 + x
      }
    } else if(e == s) {
      sameEnds = true
    }   
  }
}

class Finger {
  var start:Int = _  // start node
  var node:ActorRef = _ // successor node
}

class Node extends Actor {
  import context._

  var node_id:String = context.self.path.name
  var successor:ActorRef = _
  var predecessor: ActorRef = _
  var fingers = new Array[Finger](Settings.m)
  var keys = ArrayBuffer[String]()
  var requests: Int = _
  var cancellable: Cancellable = null

  
  def join = {    
    // select an random node in the network
    var nRand = Nodes.getRandom(context.self)
    
    // initialize finger[i].start values for newly added node
    initStarts
    
    if(nRand == null) {
      for(i <- 0 to (Settings.m - 1)) {
        fingers(i).node = context.self // set self as finger nodes
      }
      
      setPred(context.self) // set self as predecessor
      setSucc(fingers(0).node) // set the first finger table entry as the successor
      
    } else { // Chord has some nodes in it.
      initFingerTable(nRand) // initialize the finger table of the joining node
      updateOthers // update all other nodes whose finger table should refer to n
    }
 
    // create a scheduler (starts after 2 secs) that searches for keys every 1 SECOND, numRequests number of times
    cancellable = system.scheduler.schedule(Duration.create(Nodes.startSchedIn, TimeUnit.SECONDS), Duration.create(1, TimeUnit.SECONDS)) {
      
      try {
        // execute the findKey once all the nodes have joined
        if(Nodes.allJoined == true) {
          var rand_str  = scala.util.Random.alphanumeric.take(10).mkString
          
          var truncateChars = (Settings.m / 4).toInt // since 1 hex char is 4 bits
          var sha = MessageDigest.getInstance("SHA-1")
          var hexStr = sha.digest(rand_str.getBytes).map{ b => String.format("%02X", new java.lang.Integer(b & 0xff)) }.mkString
          var searchKey = Integer.parseInt(hexStr.substring(0, truncateChars), 16).toString()         
          findKey(searchKey.toInt)
          
        } else {
          // waiting for all the nodes to join
        }
      
      } catch {
        case t: Throwable => {
          Err.flag = true
        }
      }
 
    } // scheduler
  }
  
  def printFingerTable = {
    println("[Node:"+node_id+"] "+ " FINGER TABLE:")
    println("---------------------------------------------------")
    println("PREDECESSOR -> "+predecessor.path.name+", SUCCESSOR -> "+successor.path.name)
    println("---------------------------------------------------")

    for(i<-0 to (fingers.length-1)) {
      println("m:" + i + ", fingers("+i+").start = "+fingers(i).start + ", fingers("+i+").node = "+fingers(i).node.path.name)
      println("---------------------------------------------------")
    }
  }
 
  // search for a key/file on a node
  def findKey(searchKey: Int) {
    if(requests < Settings.numRequests){    
      println("[Node:"+node_id+"] "+ "requested for key = " + searchKey)

      if(requests == 0){ // Initialize total hops for this node to 0 as this is the first request 
        Nodes.hops += (node_id.toInt -> 0)
      }
      
      var succ = findSuccessor(searchKey, node_id.toInt)
      requests += 1
      
      if(Nodes.reqs.keySet.exists(_ == node_id.toInt) == true){
        Nodes.reqs(node_id.toInt) = Nodes.reqs(node_id.toInt) + 1
      } else {
        Nodes.reqs(node_id.toInt) = 1
      }       
            
    } else {
      cancellable.cancel()
    }
  }
  
  def moveKeys(newNode: Int, pred: Int): ArrayBuffer[String] = {
    var keysToMove = ArrayBuffer[String]() // keys to be moved from successor to the new node
    
    var kBuff = ""
    for(i <- 0 to (keys.length-1)) {
      if((keys(i).toInt > pred) && (keys(i).toInt <= newNode)) {
        kBuff = keys(i)
        keysToMove += kBuff
        keys -= kBuff // remove this key from the successor node
      }
    }
    
    return keysToMove
  }
  
  def initStarts = {
    for(i <- 0 to (Settings.m - 1)) {
      fingers(i) = new Finger()
      fingers(i).start = ((node_id.toInt + Math.pow(2, i)) % Settings.totalKeys).toInt // (n + 2^(i-1)) % (2^m)
    }
  }
  
  def initFingerTable(nRand: ActorRef) = {
//    println("[Node:"+node_id+"] "+ "---------------------------------------- Called InitFingerTable(nRand:"+nRand.path.name+") ---------------------------------------- ")
    
    var futureFindSuccessor = Patterns.ask(nRand, FindSuccessor(fingers(0).start, -1), TimeOut.t)
    fingers(0).node = Await.result(futureFindSuccessor, TimeOut.t.duration).asInstanceOf[ActorRef]
    var succNode = fingers(0).node
    setSucc(succNode)
    
    // set the new node's predecessor as the successor's predecessor
    var futureFindPredecessor = Patterns.ask(successor, NodePredecessor, TimeOut.t)
    var predNode = Await.result(futureFindPredecessor, TimeOut.t.duration).asInstanceOf[ActorRef]
    setPred(predNode)
    
    //set the successor's predecessor as the new node
    var setSuccPredecessor = Patterns.ask(successor, SetPredecessor(context.self), TimeOut.t)
    var z = Await.result(setSuccPredecessor, TimeOut.t.duration)
    
    // set the predecessor's successor as the new node
     var setPredSucc = Patterns.ask(predecessor, SetSuccessor(context.self), TimeOut.t)
     z = Await.result(setPredSucc, TimeOut.t.duration)
    
    // if there are only 2 nodes in the system then successor's successor is also the new node
    if(Nodes.arr.length == 2) {          
      var setSuccSucc = Patterns.ask(successor, SetSuccessor(context.self), TimeOut.t)
      z = Await.result(setSuccSucc, TimeOut.t.duration)       
    }
      
    //update successor information in finger table of the new node
    var cR = new CircularRange()
    
    for(i <- 0 to (Settings.m - 2)) {
      var nextFingerKey = fingers(i+1).start
      var prevSuccNode = fingers(i).node
            
      cR.set(nextFingerKey, node_id.toInt, prevSuccNode.path.name.toInt)  
      
      // nextFingerKey (- [node_id, prevSuccNode) 
      if((cR.x >= cR.s) && (cR.x < cR.e)) {
        fingers(i+1).node = prevSuccNode
        
      } else {
        var futureFindSuccessor_2 = Patterns.ask(nRand, FindSuccessor(fingers(i+1).start, -1), TimeOut.t)
        fingers(i+1).node = Await.result(futureFindSuccessor_2, TimeOut.t.duration).asInstanceOf[ActorRef]

      }
    }
  }
  
  def updateOthers = {
//    println("[Node:"+node_id+"] "+ "--------- Called UpdateOthers ---------")
    var nodesUpdatedMap = collection.mutable.Map[String, Boolean]()
    
    breakable {
      for(i <- 0 to (Settings.m - 1)) {
        var id = (node_id.toInt - Math.pow(2, i)).toInt // find last node p whose ith finger might be n
        if(id < 0) { id = id + Settings.totalKeys }
        
//        println("[Node:"+node_id+"] "+ "Key of previous node which might have i:"+i+"th finger as the newNode:" + node_id + " -> id = " + id)
        
        var p: ActorRef = null
        
        // If there is a node at this id start from here, or else find the predecessor(node) of this id.
        if(Nodes.present.keySet.exists(_ == id)){
          p = Nodes.present(id)
//          println("[Node:"+node_id+"] "+ "A node is already present at this id:"+id+", p -> "+ p.path.name+". No need to find Predecessor of id:"+id+".")
        } else {
          p  = findPredecessor(id, -1)
        }
        
//        println("[Node:"+node_id+"] "+ " ==========> COUNTER CLOCKWISE NODE that has the newNode:"+node_id+" as the "+i+"th finger -> " + p.path.name)
        
        // the node in counter clockwise direction should not be the self node
        if(p.path.name.toInt != node_id.toInt){
//          println("[Node:"+node_id+"] "+ "Calling Node:"+p.path.name+" ! UpdateFingerTable("+node_id+", "+i+")")
          p ! UpdateFingerTable(context.self, i)
          
        } else {
//          println("[Node:"+node_id+"] "+ "###### Reached the End. Node cannot update itself. Breaking out! ######")
          break
          
        }
      }
    }  
    
//    println("[Node:"+node_id+"] "+ "--------- Finished UpdateOthers ---------\n")
  }
  
  // check if newly added node 'n' lies between the node 'p' and p's finger at 'i' and update that finger entry.
  // Call recursively for the subsequent predecessors
  def updateFingerTable(newNode: ActorRef, i: Int) = {
//    println("[Node:"+node_id+"] "+ "--- Called updateFingerTable(newNode:"+newNode.path.name+", i:"+i+") ---")
    
    var n_id = node_id.toInt
    var fingerNode_id = fingers(i).node.path.name.toInt
    var fingerStart_key = fingers(i).start
    var newNode_id = newNode.path.name.toInt
    
    var cR = new CircularRange()
    cR.set(newNode_id, n_id, fingerNode_id)
    
    var cR_2 = new CircularRange()
    cR_2.set(fingerStart_key, n_id, newNode_id)
    
    
//    println("[Node:"+node_id+"] "+"node_id -> "+n_id+ ", newNode_id -> "+newNode_id+", fingerNode_id -> "+fingerNode_id+", fingerStart_key -> "+fingerStart_key)
    
    /*
     newNode_id (- [n_id, fingers(i).node) OR
     (n_id == fingerNode_id) and ( fingers(i).start (- (n_id, newNode_id] )
    */
    if(((cR.x >= cR.s) && (cR.x < cR.e)) || ((n_id == fingerNode_id) && ((cR_2.x > cR_2.s) && (cR_2.x <= cR_2.e)))) {
      // (s:4, x:9, e:12) || (node_id: 4, succ: 4, newNode: 9, start: 5), (node_id: 4, succ: 4, newNode: 2, start, 10)
      
//      println("[Node:"+node_id+"] "+ " Setting the finger(" + i + ") = "+newNode.path.name)
      fingers(i).node = newNode //  i = 0 is basically the successor node.
      if(i == 0) { 
        var succNode = newNode 
        setSucc(succNode)
      } 

      // Recurrence from one pred to another till the newNode from i = 0 to m
//      println("[Node:"+node_id+"] "+ " Fetching predecessor for this node.")
      var p = predecessor // get first node preceding this node
//      println("[Node:"+node_id+"] "+ " Updating finger table of the predecessor p -> " + p.path.name)
      
      if(p.path.name.toInt == newNode.path.name.toInt) {
//       println("[Node:"+node_id+"] "+ "p == newNode == " + newNode.path.name + ". Cannot call updateFingerTable on the node p that is same as the newNode. RECURSION STOPPED!")         
      } else {
//        println("*********** RECURSIVE CALL -> Node:"+p.path.name+" ! UpdateFingerTable(newNode:"+newNode.path.name+", i:"+i+") ***********")
        p ! UpdateFingerTable(newNode, i)
      }      
    } else {
//      println("[Node:"+node_id+"] [newNode:"+newNode_id+"] [i:"+i+"] DID NOT SATISY ANY CONDITION!")
    }

//    println("[Node:"+node_id+"] "+ "--- Finished updateFingerTable(newNode:"+newNode.path.name+", i:"+i+") ---")

  }
  
  // Gives the successor node for an id.
  // sourceNodeId is the node from where search for id started. It is false in other cases (initFingerTable, updateFingerTable, etc).
  def findSuccessor(id: Int, sourceNodeId: Int): ActorRef = {
//    println("[Node:"+node_id+"] "+ "@@@@@@@@@@@@@@ Called findSuccessor(id:"+id+", sourceNodeId:"+sourceNodeId+") @@@@@@@@@@@@@@")
        
    if(node_id.toInt == id.toInt) { // eg: n4 called findSuccessor(4). This is should return n4 itself.
//      println("[Node:"+node_id+"] "+ "Node:"+node_id+" called findSuccessor(id:"+id+") on itself. Returning this node itself.")
      return context.self
    }
    
    var n_pred = findPredecessor(id, sourceNodeId)
    
    if(sourceNodeId != -1) {
      if(Nodes.hops.keySet.exists(_ == sourceNodeId.toInt) == true){
        Nodes.hops(sourceNodeId.toInt) = Nodes.hops(sourceNodeId.toInt) + 1
      } else {
        Nodes.hops(sourceNodeId.toInt) = 1
      }    
    }
    
    if(n_pred.path.name.toInt == node_id.toInt) {
//      Predecessor is same as the current node ("+node_id+"). Returning self.successor
      return successor

    } else {
      var n_succ = getNodeSucc(n_pred.path.name.toInt)
      return n_succ
    }
  }
  
  def findPredecessor(id: Int, sourceNodeId: Int): ActorRef = {    
    var n_buff  = context.self
    var n_buff_succ:ActorRef = null
    
    // If the random node n (node_id) is performing findPredecessor(n), i.e id is same as n, 
    // we don't need to look anywhere else(traversal of finger table), just return the predecessor of this node.
    if(node_id.toInt == id.toInt) {
      return predecessor
    }
    
    // get the successor of n_buff
    if(n_buff.path.name.toInt == node_id.toInt){ // if you are calling self, then do a local function call (else Deadlock)
      n_buff_succ = successor
    } else {
      var futureNodeSucc = Patterns.ask(n_buff, NodeSuccessor, TimeOut.t)
      n_buff_succ = Await.result(futureNodeSucc, TimeOut.t.duration).asInstanceOf[ActorRef]   
    }
        
    var cR = new CircularRange()
    cR.set(id, n_buff.path.name.toInt, n_buff_succ.path.name.toInt)

    if(cR.sameEnds == true) { 
//      Both n_buff and n_buff_succ are the same. Returning n_buff as the predecessor
      return n_buff     
    }
    
    // keep looping unless id comes between n_buff and n_buff's successor 
    while(!((cR.x > cR.s) && (cR.x <= cR.e))) {
//      println("[Node:"+node_id+"] "+ id +" is outside (" + n_buff.path.name + ", " + n_buff_succ.path.name + "]")
      var closestFinger: ActorRef = null
      
      if(sourceNodeId != -1) {
//        println("Nodes.hops.keySet.exists(_ == sourceNodeId:"+sourceNodeId.toInt+") -> " + Nodes.hops.keySet.exists(_ == sourceNodeId.toInt))
        if(Nodes.hops.keySet.exists(_ == sourceNodeId.toInt) == true){
          Nodes.hops(sourceNodeId.toInt) = Nodes.hops(sourceNodeId.toInt) + 1
        } else {
//          println("DID NOT FIND NODE_ID in Nodes.hops: "+node_id)
          Nodes.hops(sourceNodeId.toInt) = 1
        }
      }
      
      // GET THE CLOSEST PRECEEDING FINGER. THIS IS THE NEW `N`
      if(n_buff.path.name == node_id) { // if you are calling self, then do a local function call (else Deadlock)
        closestFinger = closestPredecessorFinger(id)
        
      } else {
        var futureClosestPredFinger = Patterns.ask(n_buff, ClosestPreceedingFinger(id), TimeOut.t)
        closestFinger = Await.result(futureClosestPredFinger, TimeOut.t.duration).asInstanceOf[ActorRef]
      }      
      
      // There was no closestPreceedingFinger of node n_buff.
      // This means all entries in finger table of n_buff are greater than or equal to the id.
      if(closestFinger.path.name == n_buff.path.name) {  
        var closestFingerSuccessor:ActorRef = null
                
        // GET THE SUCCESSOR OF this new `N` FOR THE NEXT ITERATION
        if(closestFinger.path.name.toInt == node_id.toInt){ // if you are calling self, then do a local function call (else Deadlock)
          closestFingerSuccessor = successor
        } else {
          closestFingerSuccessor = getNodeSucc(closestFinger.path.name.toInt)
        }
           
        // the id should definitely lie between this closest finger and closestFinger's successor (return closestFinger node -> This is the predecessor)
        // if the id is still not between this closestFinger and closestFinger's successor then a new new node was added in the middle and this
        // new node is the predecessor of id, instead of this closestFinger
        
        var cR_2 = new CircularRange()
        cR_2.set(id, closestFinger.path.name.toInt, closestFingerSuccessor.path.name.toInt)       
        
        if((cR_2.x > cR_2.s) && (cR_2.x <= cR_2.e)){
//          println("[Node:"+node_id+"] "+ "id:"+id+" lies within node:"+closestFinger.path.name+" and its succ:"+closestFingerSuccessor.path.name)
          return closestFinger
          
        } else {
//          println("[Node:"+node_id+"] "+ "id:"+id+" does not lie within node:"+closestFinger.path.name+" and its succ:"+closestFingerSuccessor.path.name)
          return closestFingerSuccessor
        }        
      }
      
      
      n_buff = closestFinger
      
      // GET THE SUCCESSOR OF this new `N` FOR THE NEXT ITERATION
      if(n_buff.path.name.toInt == node_id.toInt){ // if you are calling self, then do a local function call (else Deadlock)
        n_buff_succ = successor
      
      } else {
          n_buff_succ = getNodeSucc(n_buff.path.name.toInt)      
      }      
      
      // update cR
      cR.set(id, n_buff.path.name.toInt, n_buff_succ.path.name.toInt)

    } // while
    
//    println("[Node:"+node_id+"] Finally, id:"+ id +" is inside (" + n_buff.path.name + ", " + n_buff_succ.path.name + "]")
    return n_buff
  }
  
  def closestPredecessorFinger(id: Int): ActorRef = {    
    for(i <- (0 to (Settings.m - 1)).reverse) {
      var fingerNode = fingers(i).node.path.name.toInt      
      var cR = new CircularRange() 

      cR.set(fingerNode, node_id.toInt, id)
      
      // fetch the ith finger node that is just before the id (k) value
      if((cR.x > cR.s) && (cR.x < cR.e)) {
        return fingers(i).node
      }
    }
    
    return context.self
  }
 
  def setSucc(succ: ActorRef) = {
    Nodes.successors += (context.self.path.name.toInt -> succ)
    successor = succ
  }
  
  def setPred(pred: ActorRef) = {
    Nodes.predecessors += (context.self.path.name.toInt -> pred)
    predecessor = pred
  }
  
  def getNodeSucc(node_id: Int): ActorRef = {
    return Nodes.successors(node_id)
  }
  
  def getNodePred(node_id: Int): ActorRef = {
    return Nodes.predecessors(node_id)
  }
  
  def receive = {
    case "join" =>
      sender ! join

    case FindSuccessor(id, sourceNodeId) => 
      sender ! findSuccessor(id, sourceNodeId)
      
    case NodeSuccessor => 
      sender ! successor
      
    case NodePredecessor => 
      sender ! predecessor 

    case SetSuccessor(succ) =>
      sender ! setSucc(succ)
      
    case SetPredecessor(pred) =>
      sender ! setPred(pred)      
      
    case ClosestPreceedingFinger(id) =>
      sender ! closestPredecessorFinger(id)
      
    case UpdateFingerTable(newNode, i) =>
      updateFingerTable(newNode, i)
    
    case MoveKeys(newNode, pred) =>
      sender ! moveKeys(newNode, pred)
      
    case PrintFingerTable => printFingerTable

    case msg: String   => println(s"Received random msg -> '$msg'")
  }  
}

class Monitor extends Actor {
  import context._
   var cancellable: Cancellable = null
   
  def receive = {
    case "wrapup" => 
      cancellable = system.scheduler.schedule(Duration.Zero, Duration.create(5000, TimeUnit.MILLISECONDS)) {        
        var totalReqs = 0
        Nodes.reqs foreach {
          case(nodeId, reqs) =>
            totalReqs += reqs
        }

        if(totalReqs == (Settings.numNodes * Settings.numRequests)) {          
          var totalHops = 0        
          Nodes.hops foreach {
            case (nodeId, hops) =>
              totalHops += hops
          }
          
          //println("TOTAL HOPS -> " + totalHops+", TOTAL REQUEST -> "+totalReqs)
          println("NO. OF NODES = " + Settings.numNodes + "\nNO. OF REQUESTS PER NODE = " + Settings.numRequests)
          var averageHops = (totalHops.toFloat/totalReqs.toFloat)
          println("AVERAGE HOPS PER REQUEST = " + averageHops)
          
          cancellable.cancel()
          system.shutdown()
        } 
      }
    
    case "shutsys" =>
      system.shutdown()  
    
    case _ => println("huh?")
  } 
}

object Chord extends App {
  Settings.numNodes = args(0).toInt
  Settings.numRequests = args(1).toInt
  
  if((Settings.numNodes < 1) || (Settings.numRequests < 0)) {
    println("\n Invalid parameters. numNodes and numRequests must be > 0.")
    System.exit(1)
  }
  
  var maxInt = Int.MaxValue
  var m = Math.floor(Math.log10(maxInt) / Math.log10(2)).toInt 
  Settings.m = m //4
  Settings.totalKeys = Math.pow(2, Settings.m).toInt
  Settings.lastKey = Settings.totalKeys - 1
  
  println("numNodes = " + Settings.numNodes)
  println("numRequests = " + Settings.numRequests)
  //println("m = " + Settings.m)
  
  var partialStr = "192.168.1."
  var ip:String = _
  var id:String = _
  
  val actorSystem = ActorSystem("ChordSystem", ConfigFactory.parseString(get_config))
  var mon = actorSystem.actorOf(Props[Monitor], name = "monitor")
  Err.act = mon
  
  // join node
  println("Adding nodes to the system (This might take a while) ...")
  for(i<-0 to (Settings.numNodes-1)) {
    ip = partialStr + i.toString()    
    id = Nodes.getKey(ip)
    var n = actorSystem.actorOf(Props[Node], name = id)
    Nodes.arr += n
    Nodes.present += (id.toInt -> n)
    
    var createNode = Patterns.ask(n, "join", TimeOut.t)
    var z = Await.result(createNode, TimeOut.t.duration)     
  }
  
  println("Finished adding all nodes to the system!")
  Thread.sleep(5000)
  Nodes.allJoined = true
  
  mon ! "wrapup"
  
  def get_config() : String = {
      var config = """
      akka {
      loglevel = "OFF"
      log-sent-messages = off
      log-received-messages = off
      }""" : String
      
      return config
  }

}

