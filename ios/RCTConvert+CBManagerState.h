#import <React/RCTConvert.h>
#import <CoreBluetooth/CoreBluetooth.h>

@interface RCTConvert (CBManagerState)

+ (id)fromCBManagerState:(CBManagerState)state;

@end
