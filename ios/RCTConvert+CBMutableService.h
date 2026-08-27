#import <React/RCTConvert.h>
#import <CoreBluetooth/CoreBluetooth.h>

@interface RCTConvert (CBMutableService)

+ (CBMutableService *)CBMutableService:(id)json;

@end
