import { AccessToken, AgentDispatchClient, RoomServiceClient } from "livekit-server-sdk"

export interface LiveKitConfig {
  url: string
  apiKey: string
  apiSecret: string
}

export interface LiveKit {
  createRoom(room: {
    name: string
    emptyTimeout: number
    departureTimeout: number
    maxParticipants: number
  }): Promise<void>
  createDispatch(room: string, agent: string, metadata: string): Promise<void>
  roomExists(room: string): Promise<boolean>
  deleteRoom(room: string): Promise<void>
  participantToken(
    grant: { room: string; identity: string; name: string; ttlSeconds: number },
  ): Promise<string>
}

/** The LiveKit Cloud clients. The SDK talks https even when the configured URL is wss. */
export function liveKitClients(config: LiveKitConfig): LiveKit {
  const host = config.url.replace(/^wss:/i, "https:").replace(/^ws:/i, "http:")
  const rooms = new RoomServiceClient(host, config.apiKey, config.apiSecret, {
    requestTimeout: 5,
    failover: false,
  })
  const dispatch = new AgentDispatchClient(host, config.apiKey, config.apiSecret, {
    requestTimeout: 5,
    failover: false,
  })
  return {
    async createRoom(room) {
      await rooms.createRoom(room)
    },
    async roomExists(room) {
      return (await rooms.listRooms([room])).length > 0
    },
    async deleteRoom(room) {
      await rooms.deleteRoom(room)
    },
    async createDispatch(room, agent, metadata) {
      await dispatch.createDispatch(room, agent, { metadata })
    },
    participantToken({ room, identity, name, ttlSeconds }) {
      const token = new AccessToken(config.apiKey, config.apiSecret, {
        identity,
        name,
        ttl: ttlSeconds,
      })
      token.addGrant({
        room,
        roomJoin: true,
        canPublish: true,
        canSubscribe: true,
        canPublishData: true,
      })
      return token.toJwt()
    },
  }
}
