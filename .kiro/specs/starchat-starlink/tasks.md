# Implementation Plan

## Phase 1: Core Infrastructure

- [x] 1. Set up project structure and build system






  - [x] 1.1 Create multi-module Maven project structure

    - Create parent POM with common dependencies
    - Create modules: novalink-core, novachat-common, novachat-bukkit, novachat-velocity, novachat-bungee, novachat-nukkit
    - Configure Java 17 for backend, Java 8/17 compatibility for plugins
    - _Requirements: 23.1-23.6_

  - [x] 1.2 Set up testing framework

    - Add JUnit 5 and jqwik dependencies
    - Configure test resources and fixtures
    - _Requirements: Testing Strategy_

- [x] 2. Implement NovaProtocol core





  - [x] 2.1 Create packet base classes and codec


    - Implement VarInt encoder/decoder
    - Create Packet abstract class with serialization interface
    - Implement ByteBuf utilities for big-endian operations
    - _Requirements: NovaProtocol Specification_

  - [x] 2.2 Write property test for VarInt round-trip

    - **Property: VarInt Encoding Round-Trip**
    - **Validates: NovaProtocol Specification**
  - [x] 2.3 Implement core packet types


    - HandshakePacket (0x01)
    - HandshakeResponsePacket (0x02)
    - ChatMessagePacket (0x03)
    - ChannelActionPacket (0x04)
    - KeepAlivePacket (0x07)
    - _Requirements: NovaProtocol Specification_
  - [x] 2.4 Write property test for packet serialization round-trip


    - **Property 13: Color Code Parsing Round-Trip**
    - **Validates: Requirements 10.2, 10.3**

- [x] 3. Checkpoint - Ensure all tests pass





  - Ensure all tests pass, ask the user if questions arise.


## Phase 2: NovaLink Backend Core

- [x] 4. Implement Netty network layer





  - [x] 4.1 Create NettyServer with boss/worker thread groups


    - Configure NioServerSocketChannel
    - Set TCP_NODELAY and SO_KEEPALIVE options
    - Implement ChannelInitializer with pipeline
    - _Requirements: 1.1, 1.2_
  - [x] 4.2 Implement frame decoder/encoder


    - Create Varint21FrameDecoder for packet boundary detection
    - Create Varint21LengthFieldPrepender for length prefix
    - _Requirements: NovaProtocol Specification_

  - [x] 4.3 Implement packet handler dispatcher

    - Create ServerNetworkHandler for routing packets
    - Implement async business logic thread pool
    - _Requirements: 3.2_

- [x] 5. Implement authentication system




  - [x] 5.1 Create AuthManager with SHA-256 hashing


    - Implement password hash verification
    - Create client credential storage
    - _Requirements: 1.1, 1.2, 1.3_
  - [x] 5.2 Write property test for authentication hash consistency


    - **Property 1: Authentication Hash Consistency**
    - **Validates: Requirements 1.1**
  - [x] 5.3 Write property test for authentication success/failure


    - **Property 2: Authentication Success/Failure Determinism**
    - **Validates: Requirements 1.2, 1.3**
  - [x] 5.4 Implement IP ban mechanism


    - Track consecutive failures per IP
    - Implement temporary ban with configurable duration
    - _Requirements: 1.5_
  - [x] 5.5 Write property test for IP ban after consecutive failures


    - **Property 3: IP Ban After Consecutive Failures**
    - **Validates: Requirements 1.5**

- [x] 6. Implement permission system




  - [x] 6.1 Create permission hierarchy model


    - Define SuperAdmin, ClientAdmin, ChannelAdmin, Player roles
    - Implement permission check logic
    - _Requirements: 2.1, 2.2, 2.3_
  - [x] 6.2 Write property test for permission hierarchy enforcement


    - **Property 4: Permission Hierarchy Enforcement**
    - **Validates: Requirements 2.7**
  - [x] 6.3 Implement super admin authentication


    - Create `/nc auth` command handler
    - Store temporary admin session
    - _Requirements: 2.2, 10.1-10.5_

- [x] 7. Checkpoint - Ensure all tests pass













  - Ensure all tests pass, ask the user if questions arise.

## Phase 3: Channel System

- [x] 8. Implement channel core





  - [x] 8.1 Create Channel model and ChannelManager


    - Implement Channel class with scope, members, config
    - Create ChannelManager for lifecycle management
    - _Requirements: 3.1, 3.4_

  - [x] 8.2 Implement message routing engine

    - Route messages based on channel scope (GLOBAL/SERVER/PRIVATE)
    - Enforce client boundary isolation for SERVER scope
    - _Requirements: 3.2, 3.5_

  - [x] 8.3 Write property test for message routing scope isolation

    - **Property 5: Message Routing Scope Isolation**
    - **Validates: Requirements 3.2, 3.5, 5.3**

- [x] 9. Implement global channels




  - [x] 9.1 Create global channel loader from config


    - Parse `global_channels` section from novalink.yml
    - Register channels with ChannelManager
    - _Requirements: 4.1_

  - [x] 9.2 Implement cross-client message routing

    - Route GLOBAL messages to all connected clients
    - Filter by permission node
    - _Requirements: 4.2, 4.3_

  - [x] 9.3 Write property test for global channel cross-client routing

    - **Property 7: Global Channel Cross-Client Routing**
    - **Validates: Requirements 4.3**

- [x] 10. Implement server channels



  - [x] 10.1 Create server channel management


    - Store channels under client configuration
    - Implement client-scoped routing
    - _Requirements: 5.1, 5.2, 5.3_

  - [x] 10.2 Implement channel templates
    - Parse `templates` section from config
    - Apply template inheritance with override support
    - _Requirements: 5.5_
  - [x] 10.3 Write property test for template inheritance



    - **Property 17: Template Inheritance**
    - **Validates: Requirements 5.5**

- [x] 11. Implement world filter

  - [x] 11.1 Create world filter logic
    - Parse `allowed_worlds` from channel config
    - Implement world membership check
    - _Requirements: 6.1, 6.4_
  - [x] 11.2 Write property test for world filter membership


    - **Property 8: World Filter Membership**
    - **Validates: Requirements 6.1, 6.2, 6.3, 6.4**

- [x] 12. Implement private channels




  - [x] 12.1 Create private channel creation logic





    - Generate unique short ID (NC-XXXX format)
    - Auto-generate password if not provided
    - _Requirements: 7.1, 7.2, 7.3_
  - [x] 12.2 Write property test for private channel ID uniqueness





    - **Property 9: Private Channel ID Uniqueness**
    - **Validates: Requirements 7.2**
  - [x] 12.3 Implement private channel access control





    - Verify client membership
    - Verify password
    - _Requirements: 7.4, 7.6_
  - [x] 12.4 Write property test for private channel client isolation





    - **Property 10: Private Channel Client Isolation**
    - **Validates: Requirements 7.4, 7.6**

- [x] 13. Checkpoint - Ensure all tests pass





  - Ensure all tests pass, ask the user if questions arise.


## Phase 4: Database and Persistence

- [x] 14. Implement database layer




  - [x] 14.1 Create DatabaseProvider interface and implementations


    - Implement MySQLProvider with HikariCP
    - Implement RedisProvider for caching
    - Implement MemoryProvider for no-database mode
    - _Requirements: 22.1, 22.2, 22.3, 22.5_

  - [x] 14.2 Create database schema and migrations

    - Create tables: players, channels, mutes, invitations
    - Implement auto-migration on startup
    - _Requirements: 22.1_

  - [x] 14.3 Implement player state persistence

    - Save/load player channel memberships
    - Save/load mute status
    - _Requirements: 22.4, 3.3_

  - [x] 14.4 Write property test for player state persistence round-trip

    - **Property 6: Player State Persistence Round-Trip**
    - **Validates: Requirements 3.3, 22.1, 22.4**

- [x] 15. Implement configuration system





  - [x] 15.1 Create YAML configuration loader


    - Parse novalink.yml with comments preservation
    - Implement auto-completion for missing fields
    - _Requirements: 18.3, 18.4, 20.1-20.6_


  - [x] 15.2 Write property test for configuration parsing round-trip


    - **Property 16: Configuration Parsing Round-Trip**
    - **Validates: Requirements 20.1-20.6**
  - [x] 15.3 Implement hot reload mechanism




    - Watch config file changes
    - Broadcast ConfigSyncPacket on reload
    - _Requirements: 18.1, 18.2, 4.5_

- [x] 16. Checkpoint - Ensure all tests pass





  - Ensure all tests pass, ask the user if questions arise.

## Phase 5: Management Features

- [x] 17. Implement invitation system






  - [x] 17.1 Create invitation code generator

    - Generate 6-character alphanumeric codes
    - Store with 24-hour TTL
    - _Requirements: 8.1, 8.2_

  - [x] 17.2 Write property test for invitation code validity

    - **Property 11: Invitation Code Validity**
    - **Validates: Requirements 8.2, 8.4**
  - [x] 17.3 Implement invitation acceptance and revocation


    - Validate code and add member
    - Mark code as used after acceptance
    - _Requirements: 8.3, 8.4, 8.5_

- [x] 18. Implement mute system

  - [x] 18.1 Create MuteManager
    - Store mute records with expiration
    - Check mute status on message send
    - _Requirements: 13.1, 13.2_


  - [x] 18.2 Implement permission-based mute scope
    - Channel admin: own channels, max 1 hour
    - Client admin: client channels, max 24 hours
    - Super admin: any channel, no limit
    - _Requirements: 13.3, 13.4, 13.5_
  - [x] 18.3 Write property test for mute duration enforcement


    - **Property 15: Mute Duration Enforcement**
    - **Validates: Requirements 13.2, 13.6**

- [x] 19. Implement message filter





  - [x] 19.1 Create sensitive word filter

    - Load built-in word list (500+ words)
    - Support custom words and regex patterns
    - _Requirements: 12.2, 12.3, 12.4_

  - [x] 19.2 Write property test for sensitive word filtering

    - **Property 14: Sensitive Word Filtering**
    - **Validates: Requirements 12.1**
  - [x] 19.3 Implement filter replacement


    - Replace matched words with `***`
    - _Requirements: 12.1_

- [x] 20. Implement announcement system





  - [x] 20.1 Create AnnouncementManager

    - Support join announcements
    - Support scheduled announcements (Cron)
    - _Requirements: 14.1, 14.2, 14.3_

  - [x] 20.2 Implement permission-based announcement scope

    - Apply same scope rules as mute system
    - _Requirements: 14.4, 14.5, 14.6_

- [x] 21. Implement Title and kick features






  - [x] 21.1 Create TitlePacket and handler

    - Support title and subtitle
    - Support color codes
    - _Requirements: 15.1, 15.5_

  - [x] 21.2 Implement kick functionality

    - Remove player from channel
    - Move to default channel
    - _Requirements: 16.1-16.5_

- [x] 22. Implement super admin remote monitoring





  - [x] 22.1 Create spy mode for super admins

    - Allow monitoring any channel remotely
    - Forward messages to admin
    - _Requirements: 17.1-17.5_

- [x] 23. Checkpoint - Ensure all tests pass




  - Ensure all tests pass, ask the user if questions arise.


## Phase 6: NovaChat Bukkit Plugin

- [x] 24. Create NovaChat-Bukkit plugin structure




  - [x] 24.1 Set up Bukkit plugin with plugin.yml


    - Define commands: novachat, nc
    - Define permissions hierarchy
    - _Requirements: 23.1_

  - [x] 24.2 Implement NetworkClient for backend connection

    - Create Netty client with reconnection logic
    - Implement packet handlers
    - _Requirements: 1.1, 1.4_

- [x] 25. Implement chat interception





  - [x] 25.1 Create ChatInterceptor for PlayerChatEvent


    - Support HYBRID and REPLACE modes
    - Forward messages to backend
    - _Requirements: 11.1, 11.2_

  - [x] 25.2 Implement message formatting

    - Load format templates from config.yml
    - Support PlaceholderAPI variables
    - Support EzColor codes
    - _Requirements: 10.1-10.6_

- [x] 26. Implement world monitoring

  - [x] 26.1 Create WorldMonitor for PlayerChangedWorldEvent
    - Detect applicable channels for new world
    - Auto-join/leave channels
    - _Requirements: 6.2, 6.3, 9.1-9.4_
  - [x] 26.2 Write property test for auto-routing world change


    - **Property 12: Auto-Routing World Change**
    - **Validates: Requirements 9.1, 9.3**

- [x] 27. Implement commands





  - [x] 27.1 Create command framework

    - Implement TabCompleter with permission filtering
    - Create base command handler
    - _Requirements: 26.1-26.4_


  - [x] 27.2 Implement player commands





    - /nc help, /nc join, /nc leave, /nc create


    - /nc invite, /nc accept, /nc toggle
    - _Requirements: 3, 7, 8, 11_
  - [x] 27.3 Implement admin commands




    - /nc mute, /nc kick, /nc announce, /nc title
    - /nc reload, /nc debug
    - _Requirements: 13-18_

- [x] 28. Implement error handling






  - [x] 28.1 Create error message system

    - Display formatted error codes
    - Provide solution suggestions
    - _Requirements: 27.1-27.4_

- [x] 29. Checkpoint - Ensure all tests pass





  - Ensure all tests pass, ask the user if questions arise.

## Phase 7: NovaChat Velocity/BungeeCord Plugins

- [x] 30. Create NovaChat-Velocity plugin






  - [x] 30.1 Set up Velocity plugin structure

    - Handle chat signing issues
    - Implement cancel-and-resend strategy
    - _Requirements: 23.2_

  - [x] 30.2 Implement cross-server message routing

    - Forward messages through backend
    - Handle server switching
    - _Requirements: 4.3, 5.3_

- [x] 31. Create NovaChat-BungeeCord plugin





  - [x] 31.1 Set up BungeeCord plugin structure

    - Similar to Velocity but with BungeeCord API
    - _Requirements: 23.3_

- [x] 32. Checkpoint - Ensure all tests pass





  - Ensure all tests pass, ask the user if questions arise.

## Phase 8: NovaChat Nukkit Plugin
-

- [x] 33. Create NovaChat-Nukkit plugin





  - [x] 33.1 Set up Nukkit plugin structure

    - Adapt Bukkit code for Nukkit API
    - Handle Bedrock-specific formatting
    - _Requirements: 23.4_


  - [x] 33.2 Implement Form API integration





    - Create GUI for channel selection
    - _Requirements: 23.4_

- [x] 34. Checkpoint - Ensure all tests pass





  - Ensure all tests pass, ask the user if questions arise.

## Phase 9: NovaChat LeviLamina Plugin (C++)

- [x] 35. Create NovaChat-LeviLamina plugin





  - [x] 35.1 Set up xmake build system

    - Configure LeviLamina SDK dependencies
    - Set up cross-platform compilation
    - _Requirements: 23.5_

  - [x] 35.2 Implement AsyncSocket client

    - Create non-blocking socket in separate thread
    - Implement thread-safe message queues
    - Handle byte order conversion (little to big endian)
    - _Requirements: 23.5, NovaProtocol Specification_


  - [x] 35.3 Implement chat hooks




    - Hook TextPacket handling
    - Implement message interception and display
    - _Requirements: 23.5_

- [x] 36. Checkpoint - Ensure all tests pass




  - Ensure all tests pass, ask the user if questions arise.

## Phase 10: Web Management Panel

- [x] 37. Create Vue.js frontend






  - [x] 37.1 Set up Vue 3 + Vite + TailwindCSS project


    - Create project structure
    - Configure routing and state management (Pinia)
    - _Requirements: 24.5_

  - [x] 37.2 Implement WebSocket connection





    - Create WebSocket service
    - Handle authentication with JWT

    - _Requirements: 24.1, 24.4_
  - [x] 37.3 Create dashboard views







    - Real-time message monitor
    - Channel management
    - Player management
    - Client status
    - _Requirements: 24.2, 24.3_

- [x] 38. Implement backend WebSocket gateway





  - [x] 38.1 Create WebSocket handler in NovaLink

    - Handle web panel connections
    - Implement JWT authentication
    - _Requirements: 24.1, 24.4_

  - [x] 38.2 Implement real-time data streaming

    - Push chat messages to web clients
    - Push status updates
    - _Requirements: 24.2_

- [x] 39. Checkpoint - Ensure all tests pass





  - Ensure all tests pass, ask the user if questions arise.

## Phase 11: API and Documentation

- [x] 40. Implement plugin API




  - [x] 40.1 Create NovaChatAPI class


    - Expose sendToChannel method
    - Create event classes (ChannelMessageEvent, PlayerChannelSwitchEvent)
    - _Requirements: 25.1-25.3_
  - [x] 40.2 Implement REST API for NovaLink


    - Create HTTP endpoints for external integration
    - Implement Webhook support
    - _Requirements: 25.4, 25.5_

- [x] 41. Create documentation





  - [x] 41.1 Write README.md with bilingual content

    - Installation guide
    - Configuration guide
    - Command reference
    - _Requirements: Documentation_

  - [x] 41.2 Create example configurations


    - Sample novalink.yml
    - Sample plugin config.yml
    - _Requirements: 20.1-20.6_

- [x] 42. Final Checkpoint - Ensure all tests pass





  - Ensure all tests pass, ask the user if questions arise.
