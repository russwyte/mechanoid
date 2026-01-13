# Order 1 Execution Trace

**Final State:** Paid

## Command Summary

- Completed: 3
- Failed: 1

## FSM + Commands Sequence Diagram

```mermaid
%%{init: {'themeCSS': '.noteText { text-align: left !important; }'}}%%
sequenceDiagram
    participant FSM as Order-1
    participant CQ as CommandQueue
    participant W as Worker

    Note over FSM: Created
    FSM->>FSM: InitiatePayment(250.0,Visa ****4242)
    Note over FSM: PaymentProcessing
    FSM->>CQ: enqueue(ProcessPayment)
    Note right of CQ: orderId=1<br/>customerId={redacted}<br/>customerName={redacted}<br/>petName=Buddy<br/>amount=250.0<br/>paymentMethod={redacted}
    CQ->>W: claim
    W->>CQ: ✅ Completed
    FSM->>FSM: PaymentSucceeded(TXN-818219)
    Note over FSM: Paid
    FSM->>CQ: enqueue(RequestShipping)
    Note right of CQ: orderId=1<br/>petName=Buddy<br/>customerName={redacted}<br/>customerAddress={redacted}<br/>correlationId=f536f9a8-10e6-4226-94bf-0242d2da2cba
    CQ->>W: claim
    W->>CQ: ❌ Failed
    FSM->>CQ: enqueue(SendNotification)
    Note right of CQ: orderId=1<br/>customerEmail={redacted}<br/>customerName={redacted}<br/>petName=Buddy<br/>notificationType=order_confirmed<br/>messageId=cf2fbad5-29aa-4b5a-a15d-13fdb8e728fa
    CQ->>W: claim
    W->>CQ: ✅ Completed
    FSM->>CQ: enqueue(NotificationCallback)
    Note right of CQ: messageId=cf2fbad5-29aa-4b5a-a15d-13fdb8e728fa<br/>delivered=true<br/>error=None
    CQ->>W: claim
    W->>CQ: ✅ Completed
    Note over FSM: Current: Paid
```

## FSM-Only Sequence Diagram

```mermaid
sequenceDiagram
    participant FSM as Order-1
    Note over FSM: Created
    FSM->>FSM: InitiatePayment(250.0,Visa ****4242)
    Note over FSM: PaymentProcessing
    FSM->>FSM: PaymentSucceeded(TXN-818219)
    Note over FSM: Paid
    Note over FSM: Current: Paid
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
    style Paid fill:#ADD8E6,stroke:#4169E1,stroke-width:3px
    style Paid fill:#90EE90,stroke:#228B22,stroke-width:4px
```
