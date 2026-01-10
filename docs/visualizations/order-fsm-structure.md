# Order FSM Structure

## State Diagram with Commands (Mermaid)

```mermaid
stateDiagram-v2
    [*] --> Created
    Created --> PaymentProcessing: InitiatePayment
    PaymentProcessing --> Paid: PaymentSucceeded
    PaymentProcessing --> Cancelled: PaymentFailed
    Paid --> ShippingRequested: RequestShipping
    ShippingRequested --> Shipped: ShipmentDispatched
    Shipped --> Delivered: DeliveryConfirmed
    note left of PaymentProcessing : enqueuePaymentCommand [ProcessPayment]
    note left of Paid : enqueueShippingCommands [RequestShipping, SendNotification]
    note left of Shipped : enqueueShippedNotification [SendNotification]
```

## FSM + Commands Flowchart

```mermaid
flowchart TB
    subgraph FSM["🔄 FSM States"]
        direction LR
        Created(("🆕 Created"))
        PaymentProcessing(("⏳ PaymentProcessing"))
        Paid(("💰 Paid"))
        ShippingRequested(("⏳ ShippingRequested"))
        Shipped(("📦 Shipped"))
        Delivered(("✅ Delivered"))
        Cancelled(("❌ Cancelled"))
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
```

## GraphViz

```dot
digraph FSM {
    rankdir=LR;
    fontsize=12;
    node [shape=ellipse, fontsize=12];
    edge [fontsize=10];

    Created [label="Created"];
    PaymentProcessing [label="PaymentProcessing\n[mechanoid.examples.PetStoreApp$.OrderFSMManager.enqueuePaymentCommand]"];
    Paid [label="Paid\n[mechanoid.examples.PetStoreApp$.OrderFSMManager.enqueueShippingCommands]"];
    ShippingRequested [label="ShippingRequested"];
    Shipped [label="Shipped\n[mechanoid.examples.PetStoreApp$.OrderFSMManager.enqueueShippedNotification]"];
    Delivered [label="Delivered"];
    Cancelled [label="Cancelled"];
    __start__ [shape=point, width=0.2];
    __start__ -> Created;

    Created -> PaymentProcessing [label="InitiatePayment"];
    PaymentProcessing -> Paid [label="PaymentSucceeded"];
    PaymentProcessing -> Cancelled [label="PaymentFailed"];
    Paid -> ShippingRequested [label="RequestShipping"];
    ShippingRequested -> Shipped [label="ShipmentDispatched"];
    Shipped -> Delivered [label="DeliveryConfirmed"];
}
```
