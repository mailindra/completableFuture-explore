#!/bin/bash

# Simple load test script
echo "Starting load test..."

# Create test users concurrently
for i in {1..100}; do
  curl -X POST http://localhost:8080/users \
    -H "Content-Type: application/json" \
    -d "{
      \"username\": \"user$i\",
      \"email\": \"user$i@example.com\",
      \"firstName\": \"First$i\",
      \"lastName\": \"Last$i\"
    }" &
done

wait
echo "Load test completed"