# Order 4 Execution Trace

**Final State:** Cancelled

## Command Summary

- Failed: 1

## FSM + Commands Sequence Diagram

```mermaid
%%{init: {'themeCSS': '.noteText { text-align: left !important; }'}}%%
sequenceDiagram
    participant FSM as Order-4
    participant CQ as CommandQueue
    participant W as Worker

    Note over FSM: Created
    FSM->>FSM: InitiatePayment(...)
    Note over FSM: PaymentProcessing
    FSM->>CQ: enqueue(ProcessPayment)
    Note right of CQ: orderId=4<br/>customerId={redacted}<br/>customerName={redacted}<br/>petName=Hoppy<br/>amount=100.0<br/>paymentMethod={redacted}
    CQ->>W: claim
    W->>CQ: ❌ Failed
    FSM->>FSM: PaymentFailed(4,CardDeclined(Generic decline))
    Note over FSM: Cancelled
    Note over FSM: Current: Cancelled
```

## FSM-Only Sequence Diagram

```mermaid
sequenceDiagram
    participant FSM as Order-4
    Note over FSM: Created
    FSM->>FSM: InitiatePayment(...)
    Note over FSM: PaymentProcessing
    FSM->>FSM: PaymentFailed(4,CardDeclined(Generic decline))
    Note over FSM: Cancelled
    Note over FSM: Current: Cancelled
```

## Flowchart with Commands

```mermaid
flowchart TB
    subgraph FSM["🔄 FSM States"]
        direction LR
        Shipped(("📦 Shipped"))
        PaymentProcessing(("⏳ PaymentProcessing"))
        ShippingRequested(("⏳ ShippingRequested"))
        Delivered(("✅ Delivered"))
        Cancelled(("❌ Cancelled"))
        Paid(("💰 Paid"))
        Created(("🆕 Created"))
        Created -->|InitiatePayment| PaymentProcessing
        PaymentProcessing -->|PaymentSucceeded| Paid
        PaymentProcessing -->|PaymentFailed| Cancelled
        Paid -->|RequestShipping| ShippingRequested
        ShippingRequested -->|ShipmentDispatched| Shipped
        Shipped -->|DeliveryConfirmed| Delivered
    end

    subgraph Commands["⚡ Commands Triggered"]
        direction LR
        ProcessPayment["💳 ProcessPayment"]
        RequestShipping["🚚 RequestShipping"]
        SendNotification["📧 SendNotification"]
    end

    PaymentProcessing -.->|on entry| ProcessPayment
    Paid -.->|on entry| RequestShipping
    Paid -.->|on entry| SendNotification
    Shipped -.->|on entry| SendNotification

    style Delivered fill:#98FB98,stroke:#228B22,stroke-width:2px
    style Cancelled fill:#FFB6C1,stroke:#DC143C,stroke-width:2px
    style ProcessPayment fill:#FFD700,stroke:#DAA520,stroke-width:2px
    style RequestShipping fill:#87CEEB,stroke:#4682B4,stroke-width:2px
    style SendNotification fill:#DDA0DD,stroke:#9932CC,stroke-width:2px

    style Created fill:#ADD8E6,stroke:#4169E1,stroke-width:3px
    style PaymentProcessing fill:#ADD8E6,stroke:#4169E1,stroke-width:3px
    style Cancelled fill:#ADD8E6,stroke:#4169E1,stroke-width:3px
    style Cancelled fill:#90EE90,stroke:#228B22,stroke-width:4px
```
