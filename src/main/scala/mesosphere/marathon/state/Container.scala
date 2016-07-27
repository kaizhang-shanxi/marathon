package mesosphere.marathon.state

import com.wix.accord.dsl._
import com.wix.accord._
import mesosphere.marathon.api.v2.Validation._

import org.apache.mesos.Protos.ContainerInfo

import scala.collection.immutable.Seq

trait Container {
  val volumes: Seq[Volume]

  def docker(): Option[Container.Docker] = {
    this match {
      case docker: Container.Docker => Some(docker)
      case _ => None
    }
  }

  def getPortMappings: Option[Seq[Container.Docker.PortMapping]] = {
    for {
      d <- docker
      n <- d.network if n == ContainerInfo.DockerInfo.Network.BRIDGE || n == ContainerInfo.DockerInfo.Network.USER
      pms <- d.portMappings
    } yield pms
  }

  def hostPorts: Option[Seq[Option[Int]]] =
    for (pms <- getPortMappings) yield pms.map(_.hostPort)

  def servicePorts: Option[Seq[Int]] =
    for (pms <- getPortMappings) yield pms.map(_.servicePort)
}

object Container {

  case class Mesos(volumes: Seq[Volume] = Seq.empty) extends Container

  case class Docker(
    volumes: Seq[Volume] = Seq.empty,
    image: String = "",
    network: Option[ContainerInfo.DockerInfo.Network] = None,
    portMappings: Option[Seq[Docker.PortMapping]] = None,
    privileged: Boolean = false,
    parameters: Seq[Parameter] = Nil,
    forcePullImage: Boolean = false) extends Container

  object Docker {

    def withDefaultPortMappings(
      volumes: Seq[Volume],
      image: String = "",
      network: Option[ContainerInfo.DockerInfo.Network] = None,
      portMappings: Option[Seq[Docker.PortMapping]] = None,
      privileged: Boolean = false,
      parameters: Seq[Parameter] = Nil,
      forcePullImage: Boolean = false): Docker = Docker(
      volumes = volumes,
      image = image,
      network = network,
      portMappings = network match {
        case Some(networkMode) if networkMode == ContainerInfo.DockerInfo.Network.BRIDGE =>
          portMappings.map(_.map { m =>
            m match {
              // backwards compat: when in BRIDGE mode, missing host ports default to zero
              case PortMapping(x, None, y, z, w, a) => PortMapping(x, Some(PortMapping.HostPortDefault), y, z, w, a)
              case _ => m
            }
          })
        case _ => portMappings
      },
      privileged = privileged,
      parameters = parameters,
      forcePullImage = forcePullImage)

    /**
      * @param containerPort The container port to expose
      * @param hostPort      The host port to bind
      * @param servicePort   The well-known port for this service
      * @param protocol      Layer 4 protocol to expose (i.e. "tcp", "udp" or "udp,tcp" for both).
      * @param name          Name of the service hosted on this port.
      * @param labels        This can be used to decorate the message with metadata to be
      *                      interpreted by external applications such as firewalls.
      */
    case class PortMapping(
      containerPort: Int = AppDefinition.RandomPortValue,
      hostPort: Option[Int] = None, // defaults to HostPortDefault for BRIDGE mode, None for USER mode
      servicePort: Int = AppDefinition.RandomPortValue,
      protocol: String = "tcp",
      name: Option[String] = None,
      labels: Map[String, String] = Map.empty[String, String])

    object PortMapping {
      val TCP = "tcp"
      val UDP = "udp"

      val HostPortDefault = AppDefinition.RandomPortValue // HostPortDefault only applies when in BRIDGE mode

      implicit val uniqueProtocols: Validator[Iterable[String]] =
        isTrue[Iterable[String]]("protocols must be unique.") { protocols =>
          protocols.size == protocols.toSet.size
        }

      implicit val portMappingValidator = validator[PortMapping] { portMapping =>
        portMapping.protocol.split(',').toIterable is uniqueProtocols and every(oneOf(TCP, UDP))
        portMapping.containerPort should be >= 0
        portMapping.hostPort.each should be >= 0
        portMapping.servicePort should be >= 0
        portMapping.name is optional(matchRegexFully(PortAssignment.PortNamePattern))
      }

      def networkHostPortValidator(docker: Docker): Validator[PortMapping] =
        isTrue[PortMapping]("hostPort is required for BRIDGE mode.") { pm =>
          docker.network match {
            case Some(ContainerInfo.DockerInfo.Network.BRIDGE) => pm.hostPort.isDefined
            case _ => true
          }
        }

      val portMappingsValidator = validator[Seq[PortMapping]] { portMappings =>
        portMappings is every(valid)
        portMappings is elementsAreUniqueByOptional(_.name, "Port names must be unique.")
      }

      def validForDocker(docker: Docker): Validator[Seq[PortMapping]] = validator[Seq[PortMapping]] { pm =>
        pm is every(valid(PortMapping.networkHostPortValidator(docker)))
      }
    }

    val validDockerContainer: Validator[Container.Docker] = validator[Container.Docker] { docker =>
      docker.portMappings is optional(PortMapping.portMappingsValidator and PortMapping.validForDocker(docker))
    }
  }

  implicit val validContainer: Validator[Container] = {
    val validGeneralContainer = validator[Container] { container =>
      container.volumes is every(valid)
    }

    new Validator[Container] {
      override def apply(container: Container): Result = container match {
        case _: Mesos => Success
        case docker: Docker => validate(docker)(Docker.validDockerContainer)
      }
    } and validGeneralContainer
  }
}

