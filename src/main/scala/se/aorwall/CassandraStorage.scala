package se.aorwall

import scala.collection.immutable.List
import scala.concurrent.Future
import me.prettyprint.cassandra.serializers.StringSerializer
import me.prettyprint.cassandra.serializers.UUIDSerializer
import me.prettyprint.cassandra.service.CassandraHostConfigurator
import me.prettyprint.cassandra.service.template.ThriftColumnFamilyTemplate
import me.prettyprint.cassandra.utils.TimeUUIDUtils
import me.prettyprint.hector.api.factory.HFactory
import se.aorwall.model.Task
import java.util.UUID
import me.prettyprint.hector.api.ddl.ComparatorType
import me.prettyprint.hector.api.ddl.KeyspaceDefinition
import me.prettyprint.cassandra.service.ThriftKsDef
import java.util.Arrays
import se.aorwall.exceptions.NotFoundException
import scala.collection.JavaConversions._

object CassandraStorage extends Storage[String, Task] {
  
  val cluster = HFactory.getOrCreateCluster("TestCluster", new CassandraHostConfigurator("localhost:9160"))
  
  def createSchema() {
    val tasks = HFactory.createColumnFamilyDefinition("TestKeyspace", "Tasks")
    val timeline = HFactory.createColumnFamilyDefinition("TestKeyspace", "Timeline")
    val newKeyspace = HFactory.createKeyspaceDefinition("TestKeyspace", ThriftKsDef.DEF_STRATEGY_CLASS, 1, Arrays.asList(tasks, timeline))
    cluster.addKeyspace(newKeyspace, true)
  }
  
  val keyspaceDef = cluster.describeKeyspace("TestKeyspace")
  if (keyspaceDef == null) {
    createSchema()
  }
  
  val keyspace = HFactory.createKeyspace("TestKeyspace", cluster)  
  val tasks = new ThriftColumnFamilyTemplate[UUID, String](keyspace, "Tasks", UUIDSerializer.get(), StringSerializer.get())
  val timeline = new ThriftColumnFamilyTemplate[String, UUID](keyspace, "Timeline", StringSerializer.get(), UUIDSerializer.get())
  
  def updateTask(id: UUID, task: Task){
    val updater = tasks.createUpdater(id)
    updater.setLong("timestamp", task.timestamp)
    updater.setString("title", task.title)
    updater.setBoolean("isDone", task.isDone)
    tasks.update(updater)
  }
  
  def create(task: Task): Future[String] = {
    val id = UUID.randomUUID()
    updateTask(id, task)
    
    val tlUp = timeline.createUpdater("tasks")
    val timeUuid = TimeUUIDUtils.getTimeUUID(task.timestamp)
    tlUp.setUUID(timeUuid, id)
    timeline.update(tlUp)
    
    Future.successful(id.toString)
  }

  protected def read(uuid: UUID): Option[Task] = {
    val res = tasks.queryColumns(uuid)
    if (res.hasResults()){
      val timestamp = res.getLong("timestamp")
      val title = res.getString("title")
      val isDone = res.getBoolean("isDone")
      Some(Task(uuid.toString, timestamp, title, isDone))  
    } else {
      None
    }
  }
  
  def read(id: String): Future[Task] = {
    val uuid = UUID.fromString(id)
    read(uuid) match {
      case Some(t) => Future.successful(t) 
      case None => Future.failed(new NotFoundException("No task found with id: " + id))
    }
  }

  def readAll(): Future[List[Task]] = { 
    val cols = timeline.queryColumns("tasks")
    val taskList = cols.getColumnNames().map { id => read(cols.getUUID(id)) }.flatten.toList
    Future.successful(taskList)
  }

  def update(task: Task): Future[Unit] = { 
    val id = UUID.fromString(task.id)
    val res = tasks.queryColumns(id)
    if(res.hasResults()){
      updateTask(id, task)
      Future.successful()
    } else {
      Future.failed(new NotFoundException("No task found with id: " + id))
    }
  }

  def delete(id: String): Future[Unit] = { 
    val uuid = UUID.fromString(id)
    read(uuid) match {
      case Some(Task(id, timestamp, _, _)) => {
        tasks.deleteRow(uuid)
        val timeUuid = TimeUUIDUtils.getTimeUUID(timestamp)
        timeline.deleteColumn("tasks", timeUuid)
        Future.successful()
      }
      case None => Future.failed(new NotFoundException("No task found with id: " + id))
    }
  }
}